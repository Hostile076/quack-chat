package io.quack.server.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 极简指标：只放“运维最少要知道”的几个计数。
 *
 * <p>没有引入 Micrometer/Prometheus 依赖，`/metrics` 直接输出 Prometheus 文本格式，
 * 够用且零成本；将来接真实监控时换成 Micrometer 即可。
 */
@Component
public class Metrics {

    private final long startedAt = System.currentTimeMillis();
    private final AtomicLong messagesAccepted = new AtomicLong();
    private final AtomicLong messagesDelivered = new AtomicLong();

    public void onMessageAccepted() {
        messagesAccepted.incrementAndGet();
    }

    public void onMessagesDelivered(int count) {
        if (count > 0) {
            messagesDelivered.addAndGet(count);
        }
    }

    public long messagesAccepted() {
        return messagesAccepted.get();
    }

    public long messagesDelivered() {
        return messagesDelivered.get();
    }

    public long uptimeSeconds() {
        return (System.currentTimeMillis() - startedAt) / 1000;
    }
}
