package io.quack.server.web;

import io.quack.common.util.AppDirs;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 文件上传 / 下载（图片、群文件、闪传都用这一套）。
 *
 * <p>文件落在 {@code data/files/<id>}，消息里只存 URL，不把二进制塞进消息表。
 * 生产环境应换成对象存储（MinIO/S3）+ 预签名 URL + 鉴权，这里先做本地实现。
 */
@RestController
public class FileController {

    private static final Pattern ID_PATTERN = Pattern.compile("^[0-9a-f]{32}$");

    /** 文件在服务器上保留 7 天，过期自动清理 */
    private static final long RETENTION_DAYS = 7;

    /** 单文件上限 300MB（视频比图片大，聊天/空间共用） */
    private static final long MAX_SIZE = 300L * 1024 * 1024;

    private final Path baseDir = AppDirs.subDir("files");
    /** 缩略图缓存目录：头像/聊天气泡按宽度生成一次，之后直接读小图 */
    private final Path thumbDir = AppDirs.subDir("thumbs");
    private final io.quack.server.service.AuthService authService;
    private final FileSigner signer;

    public FileController(io.quack.server.service.AuthService authService, FileSigner signer) {
        this.authService = authService;
        this.signer = signer;
    }

    @PostMapping("/api/upload")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "Authorization", required = false) String authorization)
            throws IOException {
        // 上传必须带登录令牌：文件接口是唯一暴露在 HTTP 上的写入口
        if (authService.validateHeader(authorization).isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录或令牌已失效"));
        }
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "文件为空"));
        }
        if (file.getSize() > MAX_SIZE) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "文件不能超过 50MB", "size", file.getSize()));
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        Files.createDirectories(baseDir);
        Path target = baseDir.resolve(id);
        file.transferTo(target);
        Files.setLastModifiedTime(target, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));

        String name = file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()
                ? "文件" : file.getOriginalFilename();
        return ResponseEntity.ok(Map.of(
                "id", id,
                "name", name,
                "size", file.getSize(),
                "url", signer.url(id)));
    }

    /** 每小时清理一次超过 7 天的文件 */
    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 60_000L)
    public void cleanExpired() {
        if (!Files.isDirectory(baseDir)) {
            return;
        }
        long deadline = System.currentTimeMillis() - RETENTION_DAYS * 24 * 3_600_000L;
        int removed = 0;
        try (var stream = Files.list(baseDir)) {
            for (Path path : stream.toList()) {
                try {
                    if (Files.isRegularFile(path)
                            && Files.getLastModifiedTime(path).toMillis() < deadline) {
                        Files.deleteIfExists(path);
                        removed++;
                    }
                } catch (IOException ignored) {
                    // 单个文件删不掉不影响其它文件
                }
            }
        } catch (IOException ignored) {
            return;
        }
        if (removed > 0) {
            org.slf4j.LoggerFactory.getLogger(FileController.class)
                    .info("清理过期文件 {} 个（保留 {} 天）", removed, RETENTION_DAYS);
        }
    }

    /**
     * 按文件头判断图片类型。
     *
     * <p>文件落盘时名字是 32 位 hex（没有扩展名），Windows 的
     * {@code Files.probeContentType} 直接返回 null，于是图片也会被当成附件下载。
     * 客户端 <img> 预览需要 inline，所以这里按 magic bytes 兜底。
     */
    private static String sniffImageType(Path file) {
        byte[] head = new byte[12];
        int read;
        try (var in = Files.newInputStream(file)) {
            read = in.read(head);
        } catch (IOException error) {
            return null;
        }
        if (read >= 8 && (head[0] & 0xFF) == 0x89 && head[1] == 0x50
                && head[2] == 0x4E && head[3] == 0x47) {
            return "image/png";
        }
        if (read >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8
                && (head[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (read >= 6 && head[0] == 'G' && head[1] == 'I' && head[2] == 'F') {
            return "image/gif";
        }
        if (read >= 2 && head[0] == 'B' && head[1] == 'M') {
            return "image/bmp";
        }
        if (read >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    /** 按文件头识别视频类型：文件落盘没有扩展名，否则播放器会收到 octet-stream */
    private static String sniffVideoType(Path file) {
        byte[] head = new byte[16];
        int read;
        try (var in = Files.newInputStream(file)) {
            read = in.read(head);
        } catch (IOException error) {
            return null;
        }
        if (read >= 8 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') {
            return "video/mp4";
        }
        if (read >= 4 && (head[0] & 0xFF) == 0x1A && (head[1] & 0xFF) == 0x45
                && (head[2] & 0xFF) == 0xDF && (head[3] & 0xFF) == 0xA3) {
            return "video/webm";
        }
        if (read >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'A' && head[9] == 'V' && head[10] == 'I') {
            return "video/x-msvideo";
        }
        if (read >= 3 && head[0] == 'F' && head[1] == 'L' && head[2] == 'V') {
            return "video/x-flv";
        }
        return null;
    }

    @GetMapping("/api/files/{id}")    public ResponseEntity<Resource> download(
            @PathVariable("id") String id,
            @RequestParam(value = "exp", required = false) Long expires,
            @RequestParam(value = "sig", required = false) String signature,
            @RequestParam(value = "w", required = false) Integer width,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) throws IOException {
        if (!ID_PATTERN.matcher(id).matches()) {
            return ResponseEntity.badRequest().build();
        }
        // 新上传的文件都带能力签名；历史消息里的旧 URL 没有签名，继续放行（id 本身是 128 位随机）
        if (expires != null || signature != null) {
            if (expires == null || !signer.verify(id, expires, signature)) {
                return ResponseEntity.status(403).build();
            }
        }
        Path file = baseDir.resolve(id);
        if (!Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }
        String contentType = sniffImageType(file);
        if (contentType == null) {
            contentType = sniffVideoType(file);
        }
        if (contentType == null) {
            contentType = Files.probeContentType(file);
        }
        MediaType mediaType = contentType == null
                ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(contentType);
        boolean image = contentType != null && contentType.startsWith("image/");
        // 下载即“续期”：按 mtime 清理的策略下，正在被使用的文件不能被误删
        try {
            Files.setLastModifiedTime(file,
                    java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException ignored) {
            // 续期失败不影响本次下载
        }
        String encoded = URLEncoder.encode(id, StandardCharsets.UTF_8);
        boolean inline = image || mediaType.toString().startsWith("video/");
        String disposition = (inline ? "inline" : "attachment") + "; filename=\"" + encoded + "\"";
        // 头像/气泡只要几百像素：先按宽度生成缩略图，远程客户端不用下载几 MB 的原图
        if (width != null && width >= 32 && width <= 1280 && image) {
            Path thumb = thumbnail(file, id, width, contentType);
            if (thumb != null) {
                MediaType thumbType = contentType != null && contentType.contains("png")
                        ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
                return ResponseEntity.ok()
                        .contentType(thumbType)
                        .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                        .header("X-Content-Type-Options", "nosniff")
                        .contentLength(Files.size(thumb))
                        .body(new FileSystemResource(thumb));
            }
        }
        long total = Files.size(file);

        // 视频播放器（JavaFX Media / 浏览器）会先发 Range 请求，必须回 206 + Content-Range
        long[] range = parseRange(rangeHeader, total);
        if (range != null) {
            long start = range[0];
            long end = range[1];
            InputStreamResource slice = new InputStreamResource(slice(file, start, end - start + 1));
            return ResponseEntity.status(206)
                    .contentType(mediaType)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + total)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                    .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                    .header("X-Content-Type-Options", "nosniff")
                    .contentLength(end - start + 1)
                    .body(slice);
        }
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                // 只有图片/视频允许内联渲染；其它类型强制下载，避免上传 .html 造成存储型 XSS
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                .header("X-Content-Type-Options", "nosniff")
                .contentLength(total)
                .body(new FileSystemResource(file));
    }

    /**
     * 生成/复用缩略图。文件 id 是内容唯一的，生成一次后永久复用；失败就退回原图。
     */
    private Path thumbnail(Path file, String id, int width, String contentType) {
        try {
            boolean png = contentType != null && contentType.contains("png");
            String suffix = png ? ".png" : ".jpg";
            Files.createDirectories(thumbDir);
            Path target = thumbDir.resolve(id + "-" + width + suffix);
            if (Files.isRegularFile(target)) {
                return target;
            }
            java.awt.image.BufferedImage source = javax.imageio.ImageIO.read(file.toFile());
            if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) {
                return null;
            }
            int w = Math.min(width, source.getWidth());
            int h = Math.max(1, (int) Math.round(source.getHeight() * (w / (double) source.getWidth())));
            int type = png ? java.awt.image.BufferedImage.TYPE_INT_ARGB
                    : java.awt.image.BufferedImage.TYPE_INT_RGB;
            java.awt.image.BufferedImage scaled = new java.awt.image.BufferedImage(w, h, type);
            java.awt.Graphics2D graphics = scaled.createGraphics();
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
                    java.awt.RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, w, h, null);
            graphics.dispose();
            Path temp = thumbDir.resolve(id + "-" + width + suffix + ".tmp");
            javax.imageio.ImageIO.write(scaled, png ? "png" : "jpg", temp.toFile());
            Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return target;
        } catch (IOException error) {
            return null;
        }
    }
    /** 解析单段 Range：bytes=start-end / bytes=start- / bytes=-suffix；返回 [start, end] */
    private static long[] parseRange(String header, long total) {
        if (header == null || !header.startsWith("bytes=") || total <= 0) {
            return null;
        }
        String spec = header.substring("bytes=".length()).trim();
        if (spec.contains(",")) {
            return null; // 多段不支持，退回整文件
        }
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }
        try {
            String startText = spec.substring(0, dash).trim();
            String endText = spec.substring(dash + 1).trim();
            long start;
            long end;
            if (startText.isEmpty()) {
                long suffix = Long.parseLong(endText);
                if (suffix <= 0) {
                    return null;
                }
                start = Math.max(0, total - suffix);
                end = total - 1;
            } else {
                start = Long.parseLong(startText);
                end = endText.isEmpty() ? total - 1 : Long.parseLong(endText);
            }
            if (start < 0 || start >= total) {
                return null;
            }
            end = Math.min(end, total - 1);
            if (end < start) {
                return null;
            }
            return new long[]{start, end};
        } catch (NumberFormatException error) {
            return null;
        }
    }

    /** 只读文件的一段，避免把整个视频读进内存 */
    private static java.io.InputStream slice(Path file, long start, long length) throws IOException {
        java.io.InputStream raw = Files.newInputStream(file);
        long skipped = raw.skip(start);
        while (skipped < start) {
            long more = raw.skip(start - skipped);
            if (more <= 0) {
                break;
            }
            skipped += more;
        }
        return new java.io.FilterInputStream(raw) {
            private long remaining = length;

            @Override
            public int read() throws IOException {
                if (remaining <= 0) {
                    return -1;
                }
                int value = super.read();
                if (value >= 0) {
                    remaining--;
                }
                return value;
            }

            @Override
            public int read(byte[] buffer, int offset, int count) throws IOException {
                if (remaining <= 0) {
                    return -1;
                }
                int read = super.read(buffer, offset, (int) Math.min(count, remaining));
                if (read > 0) {
                    remaining -= read;
                }
                return read;
            }
        };
    }
}
