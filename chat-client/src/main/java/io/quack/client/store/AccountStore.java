package io.quack.client.store;

import io.quack.common.model.User;
import io.quack.common.util.AppDirs;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本机账号簿：记住这台设备登录过的 QC 号，用于「切换账号」和「离线登录」。
 *
 * <p>安全取舍：
 * <ul>
 *   <li>密码本身**不落盘**；在线登录成功后保存一个 PBKDF2 校验值（随机盐），
 *       只用于离线时校验“确实是这台设备上登录过这个账号的人”；</li>
 *   <li>离线模式没有服务端鉴权，只能看本地缓存，不能发消息。</li>
 * </ul>
 */
public class AccountStore {

    private static final int PBKDF2_ITERATIONS = 120_000;

    /** 落盘的信息：QC 号、用户名/昵称/头像，以及离线校验值（盐 + 哈希） */
    public record SavedAccount(long qcId, String username, String nickname, String avatar,
                               String salt, String passwordHash) {
        public String display() {
            String name = nickname == null || nickname.isBlank() ? username : nickname;
            return name + "（" + qcId + "）";
        }

        /** 是否具备离线登录条件（在线登录过一次） */
        public boolean canLoginOffline() {
            return salt != null && !salt.isBlank() && passwordHash != null && !passwordHash.isBlank();
        }
    }

    private final Path file = AppDirs.dataDir().resolve("accounts.properties");
    private final List<SavedAccount> accounts = new CopyOnWriteArrayList<>();
    /** 仅内存：QC 号 -> 密码（本次运行内切换账号用） */
    private final Map<Long, String> sessionPasswords = new ConcurrentHashMap<>();
    /** 上次连的服务器地址：跨局域网 / 虚拟机测试时不用每次重填 */
    private volatile String lastServerUrl = "ws://127.0.0.1:8080/ws";
    /** 本机设备标识：同一设备重登会顶掉旧连接，不同设备可同时在线 */
    private volatile String deviceId;

    public AccountStore() {
        load();
        if (deviceId == null || deviceId.isBlank()) {
            deviceId = java.util.UUID.randomUUID().toString().replace("-", "");
            save();
        }
    }

    public String deviceId() {
        return deviceId;
    }

    public String lastServerUrl() {
        return lastServerUrl;
    }

    public void rememberServer(String url) {
        if (url == null || url.isBlank() || url.equals(lastServerUrl)) {
            return;
        }
        lastServerUrl = url.trim();
        save();
    }

    public List<SavedAccount> accounts() {
        return List.copyOf(accounts);
    }

    /** 登录成功后记住这个账号：缓存密码（本次运行）+ 保存离线校验值 */
    public void remember(User user, String password) {
        if (user == null) {
            return;
        }
        sessionPasswords.put(user.qcId(), password);
        SavedAccount previous = byQcId(user.qcId()).orElse(null);
        String salt = previous == null ? null : previous.salt();
        String hash = previous == null ? null : previous.passwordHash();
        if (password != null && !password.isEmpty()) {
            byte[] newSalt = new byte[16];
            new SecureRandom().nextBytes(newSalt);
            salt = Base64.getEncoder().encodeToString(newSalt);
            hash = hash(password, newSalt);
        }
        accounts.removeIf(a -> a.qcId() == user.qcId());
        accounts.add(0, new SavedAccount(user.qcId(), user.username(), user.nickname(),
                user.avatar(), salt, hash));
        save();
    }

    public Optional<String> passwordOf(long qcId) {
        return Optional.ofNullable(sessionPasswords.get(qcId));
    }

    public Optional<SavedAccount> byQcId(long qcId) {
        return accounts.stream().filter(a -> a.qcId() == qcId).findFirst();
    }

    /**
     * 离线登录校验：账号在本机登录过、且密码与本地保存的 PBKDF2 校验值一致。
     */
    public boolean verifyOffline(long qcId, String password) {
        SavedAccount account = byQcId(qcId).orElse(null);
        if (account == null || !account.canLoginOffline() || password == null) {
            return false;
        }
        try {
            byte[] salt = Base64.getDecoder().decode(account.salt());
            String actual = hash(password, salt);
            return MessageDigest.isEqual(
                    actual.getBytes(StandardCharsets.UTF_8),
                    account.passwordHash().getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static String hash(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt,
                    PBKDF2_ITERATIONS, 256);
            byte[] bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(bytes);
        } catch (Exception error) {
            throw new IllegalStateException("无法计算离线校验值", error);
        }
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException ignored) {
            return;
        }
        int count = parseInt(properties.getProperty("count"), 0);
        String server = properties.getProperty("server");
        if (server != null && !server.isBlank()) {
            lastServerUrl = server.trim();
        }
        String device = properties.getProperty("deviceId");
        if (device != null && !device.isBlank()) {
            deviceId = device.trim();
        }
        List<SavedAccount> loaded = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long qcId = parseInt(properties.getProperty("a" + i + ".qc"), 0);
            if (qcId <= 0) {
                continue;
            }
            loaded.add(new SavedAccount(qcId,
                    properties.getProperty("a" + i + ".username", ""),
                    properties.getProperty("a" + i + ".nickname", ""),
                    properties.getProperty("a" + i + ".avatar", null),
                    properties.getProperty("a" + i + ".salt", null),
                    properties.getProperty("a" + i + ".hash", null)));
        }
        accounts.addAll(loaded);
    }

    private void save() {
        Properties properties = new Properties();
        AtomicInteger index = new AtomicInteger();
        properties.setProperty("count", String.valueOf(accounts.size()));
        properties.setProperty("server", lastServerUrl);
        properties.setProperty("deviceId", deviceId == null ? "" : deviceId);
        for (SavedAccount account : accounts) {
            int i = index.getAndIncrement();
            properties.setProperty("a" + i + ".qc", String.valueOf(account.qcId()));
            properties.setProperty("a" + i + ".username", account.username() == null ? "" : account.username());
            properties.setProperty("a" + i + ".nickname", account.nickname() == null ? "" : account.nickname());
            if (account.avatar() != null) {
                properties.setProperty("a" + i + ".avatar", account.avatar());
            }
            if (account.salt() != null) {
                properties.setProperty("a" + i + ".salt", account.salt());
            }
            if (account.passwordHash() != null) {
                properties.setProperty("a" + i + ".hash", account.passwordHash());
            }
        }
        try (OutputStream out = Files.newOutputStream(file)) {
            properties.store(out, "Quack Chat accounts (password verifier only, no plaintext)");
        } catch (IOException ignored) {
            // 落盘失败不影响本次使用
        }
    }

    private static int parseInt(String text, int fallback) {
        try {
            return text == null ? fallback : Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
