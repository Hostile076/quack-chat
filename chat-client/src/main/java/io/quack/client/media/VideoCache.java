package io.quack.client.media;

import io.quack.common.util.AppDirs;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

/**
 * 播放前把视频下载到本地缓存，再用 file: 播放。
 *
 * <p>JavaFX 直接播 HTTP 源时会走它自己的磁盘缓存，部分环境会报
 * “Couldn't create backing cache”；先落盘可以绕开这一层，也方便把文件交给系统播放器。
 */
public final class VideoCache {

    private VideoCache() {
    }

    public static Path local(String url) throws Exception {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("视频地址为空");
        }
        if (url.startsWith("file:")) {
            return Path.of(URI.create(url));
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException("不支持的视频地址：" + url);
        }
        Path dir = AppDirs.subDir("video-cache");
        Path target = dir.resolve(hash(url) + ".mp4");
        if (Files.isRegularFile(target) && Files.size(target) > 0) {
            return target;
        }
        Path temp = dir.resolve(target.getFileName() + ".part");
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();
        HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(temp));
        if (response.statusCode() != 200) {
            Files.deleteIfExists(temp);
            throw new IllegalStateException("视频下载失败，HTTP " + response.statusCode());
        }
        Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    private static String hash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
