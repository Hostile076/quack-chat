package io.quack.client.net;

import io.quack.common.protocol.JsonCodec;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 文件上传：把本地文件 POST 到服务端 {@code /api/upload}，拿回可访问的 URL。
 *
 * <p>用 JDK 自带的 HttpClient 手写 multipart，不引第三方库。
 * 消息里只存 URL，图片/文件都由 HTTP 下载，不走 WebSocket 大包。
 */
public final class FileUploader {

    private static final long MAX_SIZE = 300L * 1024 * 1024;

    /** 上传结果 */
    public record Uploaded(String url, String name, long size) {
    }

    private FileUploader() {
    }

    /** ws://host:8080/ws -> http://host:8080 */
    public static String httpBase(String websocketUrl) {
        if (websocketUrl == null) {
            return "http://127.0.0.1:8080";
        }
        String base = websocketUrl.trim();
        if (base.startsWith("wss://")) {
            base = "https://" + base.substring(6);
        } else if (base.startsWith("ws://")) {
            base = "http://" + base.substring(5);
        }
        if (base.endsWith("/ws")) {
            base = base.substring(0, base.length() - 3);
        }
        return base;
    }

    public static CompletableFuture<Uploaded> upload(String websocketUrl, Path file) {
        return upload(websocketUrl, file, null);
    }

    /** @param token 登录令牌；服务端要求上传必须带 Authorization: Bearer */
    public static CompletableFuture<Uploaded> upload(String websocketUrl, Path file, String token) {
        String base = httpBase(websocketUrl);
        String boundary = "----quack" + UUID.randomUUID().toString().replace("-", "");
        long size;
        try {
            size = Files.size(file);
        } catch (IOException error) {
            return CompletableFuture.failedFuture(error);
        }
        if (size > MAX_SIZE) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("文件不能超过 300MB"));
        }
        String fileName = file.getFileName().toString().replace("\"", "'");
        byte[] prefix = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

        // 流式上传：300MB 的视频不能整个读进内存
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + "/api/upload"))
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> {
                    try {
                        return new java.io.SequenceInputStream(
                                new java.io.ByteArrayInputStream(prefix),
                                new java.io.SequenceInputStream(
                                        Files.newInputStream(file),
                                        new java.io.ByteArrayInputStream(suffix)));
                    } catch (IOException error) {
                        throw new java.io.UncheckedIOException(error);
                    }
                }));
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpRequest request = builder.build();

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new IllegalStateException(response.statusCode() == 401
                                ? "HTTP 401（登录状态失效）"
                                : "HTTP " + response.statusCode());
                    }
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> json = JsonCodec.mapper()
                                .readValue(response.body(), Map.class);
                        String url = String.valueOf(json.get("url"));
                        String name = String.valueOf(json.get("name"));
                        long uploadedSize = ((Number) json.get("size")).longValue();
                        return new Uploaded(base + url, name, uploadedSize);
                    } catch (IOException e) {
                        throw new IllegalStateException("上传响应解析失败", e);
                    }
                });
    }
}
