package io.quack.server.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录令牌：给 HTTP 接口（文件上传等）用。
 *
 * <p>WS 连接本身仍用 login 命令鉴权；令牌只在 HTTP 层做“这是哪个已登录用户”的判断。
 * 令牌只在内存里，服务端重启后客户端重新登录即可（客户端本来就会重连重登）。
 */
@Service
public class AuthService {

    private static final long TTL_MILLIS = 7L * 24 * 3600 * 1000;

    /** 令牌绑定的身份 */
    public record TokenInfo(String userId, String deviceId, long expiresAt) {
        public boolean expired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    private final Map<String, TokenInfo> tokens = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public String issue(String userId, String deviceId) {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        tokens.put(token, new TokenInfo(userId, deviceId, System.currentTimeMillis() + TTL_MILLIS));
        return token;
    }

    public Optional<TokenInfo> validate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        TokenInfo info = tokens.get(token.trim());
        if (info == null) {
            return Optional.empty();
        }
        if (info.expired()) {
            tokens.remove(token.trim());
            return Optional.empty();
        }
        return Optional.of(info);
    }

    /** 从 "Bearer xxx" 里取令牌并校验 */
    public Optional<TokenInfo> validateHeader(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return Optional.empty();
        }
        String value = authorization.trim();
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            value = value.substring(7).trim();
        }
        return validate(value);
    }

    public void revoke(String token) {
        if (token != null) {
            tokens.remove(token);
        }
    }

    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 600_000L)
    public void cleanExpired() {
        tokens.entrySet().removeIf(entry -> entry.getValue().expired());
    }
}
