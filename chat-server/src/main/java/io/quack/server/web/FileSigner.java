package io.quack.server.web;

import io.quack.common.util.AppDirs;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 文件下载签名。
 *
 * <p>图片/文件由 JavaFX 的 Image / 浏览器直接按 URL 拉取，没法带 Authorization 头，
 * 所以用“能力 URL”：{@code /api/files/{id}?exp=...&sig=hmac(id:exp)}。
 * 签名密钥保存在 data/file-signing.key，重启后旧链接仍然有效。
 */
@Component
public class FileSigner {

    private static final Logger log = LoggerFactory.getLogger(FileSigner.class);
    private static final long TTL_MILLIS = 7L * 24 * 3600 * 1000;

    private final Path keyFile = AppDirs.dataDir().resolve("file-signing.key");
    private byte[] secret;

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(keyFile.getParent());
            if (Files.isRegularFile(keyFile)) {
                secret = HexFormat.of().parseHex(Files.readString(keyFile).trim());
            } else {
                byte[] bytes = new byte[32];
                new SecureRandom().nextBytes(bytes);
                secret = bytes;
                Files.writeString(keyFile, HexFormat.of().formatHex(bytes));
            }
        } catch (Exception error) {
            log.warn("读取文件签名密钥失败，本次运行使用临时密钥：{}", error.toString());
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            secret = bytes;
        }
    }

    /** 生成带签名的下载地址（相对路径，客户端会拼上 http base） */
    public String url(String fileId) {
        long expires = System.currentTimeMillis() + TTL_MILLIS;
        return "/api/files/" + fileId + "?exp=" + expires + "&sig=" + sign(fileId, expires);
    }

    /** 校验能力 URL；exp/sig 缺失时返回 false（旧数据由控制器决定是否放行） */
    public boolean verify(String fileId, long expires, String signature) {
        if (signature == null || signature.isBlank() || expires <= 0) {
            return false;
        }
        if (System.currentTimeMillis() > expires) {
            return false;
        }
        byte[] expected = sign(fileId, expires).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] actual = signature.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    private String sign(String fileId, long expires) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] digest = mac.doFinal((fileId + ":" + expires)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            throw new IllegalStateException("无法计算文件签名", error);
        }
    }

    /** 测试/运维：密钥文件位置 */
    public Path keyFile() {
        return keyFile;
    }

    /** 仅测试用：删除密钥（会重新生成） */
    public void deleteKeyForTest() throws IOException {
        Files.deleteIfExists(keyFile);
    }
}
