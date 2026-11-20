package io.quack.server.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 极简限流：登录失败按 IP、消息发送按连接。
 *
 * <p>解决两个最现实的问题：爆破密码、单连接刷消息。不是网关级方案，
 * 但对单机部署足够，且没有引入额外依赖。
 */
@Component
public class RateLimiter {

    /** 5 分钟内允许的登录失败次数 */
    private static final int LOGIN_FAIL_LIMIT = 10;
    private static final long LOGIN_WINDOW_MS = 5L * 60 * 1000;
    /** 单连接每秒最多发送多少条消息 */
    private static final int MESSAGE_PER_SECOND = 50;

    private final Map<String, Deque<Long>> loginFailures = new ConcurrentHashMap<>();
    private final Map<String, long[]> messageWindows = new ConcurrentHashMap<>();

    public boolean loginBlocked(String ip) {
        Deque<Long> failures = loginFailures.get(ip);
        if (failures == null) {
            return false;
        }
        long deadline = System.currentTimeMillis() - LOGIN_WINDOW_MS;
        synchronized (failures) {
            while (!failures.isEmpty() && failures.peekFirst() < deadline) {
                failures.pollFirst();
            }
            return failures.size() >= LOGIN_FAIL_LIMIT;
        }
    }

    public void recordLoginFailure(String ip) {
        Deque<Long> failures = loginFailures.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (failures) {
            failures.addLast(System.currentTimeMillis());
        }
    }

    public void clearLoginFailures(String ip) {
        loginFailures.remove(ip);
    }

    /** 单连接消息令牌桶：1 秒窗口 */
    public boolean allowMessage(String sessionId) {
        long now = System.currentTimeMillis();
        long[] window = messageWindows.computeIfAbsent(sessionId, k -> new long[]{now, 0});
        synchronized (window) {
            if (now - window[0] >= 1000) {
                window[0] = now;
                window[1] = 0;
            }
            if (window[1] >= MESSAGE_PER_SECOND) {
                return false;
            }
            window[1]++;
            return true;
        }
    }

    public void forgetSession(String sessionId) {
        messageWindows.remove(sessionId);
    }

    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 600_000L)
    public void cleanExpired() {
        long deadline = System.currentTimeMillis() - LOGIN_WINDOW_MS;
        loginFailures.entrySet().removeIf(entry -> {
            Deque<Long> failures = entry.getValue();
            synchronized (failures) {
                return failures.isEmpty() || failures.peekLast() < deadline;
            }
        });
    }
}
