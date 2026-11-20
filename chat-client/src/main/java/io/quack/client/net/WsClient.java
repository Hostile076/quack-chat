package io.quack.client.net;

import io.quack.common.protocol.Envelope;
import io.quack.common.protocol.JsonCodec;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WebSocket 客户端（基于 JDK 自带的 java.net.http，不引第三方网络库）。
 *
 * <p>职责边界：
 * <ul>
 *   <li>连接 / 断线 / 心跳；</li>
 *   <li>请求-响应配对：给每帧打 seq，响应回来按 seq 唤醒对应的 {@link CompletableFuture}；</li>
 *   <li>分片重组：一条大消息可能被拆成多个文本帧（last=false），必须攒够再解析；</li>
 *   <li>把“服务端主动推送”交给上层 Listener。</li>
 * </ul>
 *
 * <p>注意：JDK 的 WebSocket 不允许在上一次 sendText 未完成时再次发送，
 * 否则抛 IllegalStateException。骨架阶段直接依赖“发送很快完成”，
 * 生产实现应该改成发送队列 + 单线程 drain。
 */
public class WsClient {

    /** 上层关心的事件 */
    public interface Listener {
        default void onOpen() {
        }

        default void onEnvelope(Envelope envelope) {
        }

        default void onClosed(int code, String reason) {
        }

        default void onError(Throwable error) {
        }
    }

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final AtomicLong seqGen = new AtomicLong();
    private final Map<Long, CompletableFuture<Envelope>> pending = new ConcurrentHashMap<>();
    private final StringBuilder fragment = new StringBuilder();

    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ws-heartbeat");
        t.setDaemon(true);
        return t;
    });

    private volatile WebSocket socket;
    private volatile ScheduledFuture<?> heartbeatTask;
    private volatile Listener listener = new Listener() {
    };
    /** 服务端主动关闭时的原因（如异地登录踢下线） */
    private volatile String closeReason;
    /** onClose 和 onError 可能先后都来，保证上层只收到一次“已断开” */
    private volatile boolean closeNotified;

    public void setListener(Listener listener) {
        this.listener = listener == null ? new Listener() {
        } : listener;
    }

    public boolean isOpen() {
        WebSocket ws = socket;
        return ws != null && !ws.isOutputClosed();
    }

    public String closeReason() {
        return closeReason;
    }

    public CompletableFuture<Void> connect(String url) {
        closeNotified = false;
        return httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(url), new SocketListener())
                .thenAccept(ws -> {
                    this.socket = ws;
                    startHeartbeat();
                });
    }

    /** 发送一个不需要响应的命令（如心跳、typing） */
    public void send(String cmd, Object data) {
        send(new Envelope(cmd, seqGen.incrementAndGet(), Envelope.CODE_OK, null, encode(data)));
    }

    /** 发送请求并等待同 seq 的响应 */
    public CompletableFuture<Envelope> request(String cmd, Object data, Duration timeout) {
        long seq = seqGen.incrementAndGet();
        CompletableFuture<Envelope> future = new CompletableFuture<>();
        pending.put(seq, future);
        try {
            send(new Envelope(cmd, seq, Envelope.CODE_OK, null, encode(data)));
        } catch (RuntimeException e) {
            pending.remove(seq);
            future.completeExceptionally(e);
            return future;
        }
        if (timeout != null) {
            future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        future.whenComplete((v, e) -> pending.remove(seq));
        return future;
    }

    public void send(Envelope envelope) {
        WebSocket ws = socket;
        if (ws == null) {
            throw new IllegalStateException("尚未连接服务器");
        }
        ws.sendText(JsonCodec.encode(envelope), true)
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        listener.onError(error);
                    }
                });
    }

    public void close() {
        stopHeartbeat();
        WebSocket ws = socket;
        if (ws != null) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").exceptionally(e -> null);
        }
        socket = null;
    }

    private static String encode(Object data) {
        return data == null ? null : JsonCodec.toJson(data);
    }

    private void startHeartbeat() {
        stopHeartbeat();
        // 25s 一次：小于常见网关 60s 空闲回收，也小于服务端 120s idle timeout
        heartbeatTask = heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (isOpen()) {
                send("heartbeat", null);
            }
        }, 25, 25, TimeUnit.SECONDS);
    }

    private void stopHeartbeat() {
        ScheduledFuture<?> task = heartbeatTask;
        if (task != null) {
            task.cancel(false);
            heartbeatTask = null;
        }
    }

    private void failPending(Throwable error) {
        pending.forEach((seq, future) -> future.completeExceptionally(error));
        pending.clear();
    }

    /**
     * JDK WebSocket 回调都在它自己的线程上，绝不能在这里直接碰 UI —— 上层负责切到 FX 线程。
     */
    private final class SocketListener implements WebSocket.Listener {

        @Override
        public void onOpen(WebSocket webSocket) {
            socket = webSocket;
            webSocket.request(1);
            listener.onOpen();
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            fragment.append(data);
            if (last) {
                String raw = fragment.toString();
                fragment.setLength(0);
                Envelope envelope = JsonCodec.parse(raw);
                if (envelope != null) {
                    CompletableFuture<Envelope> waiting = envelope.seq() > 0 ? pending.remove(envelope.seq()) : null;
                    if (waiting != null) {
                        waiting.complete(envelope);
                    } else {
                        listener.onEnvelope(envelope);
                    }
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            socket = null;
            stopHeartbeat();
            closeReason = reason;
            failPending(new IllegalStateException("连接已关闭: " + statusCode + " " + reason));
            notifyClosedOnce(statusCode, reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            socket = null;
            stopHeartbeat();
            failPending(error);
            listener.onError(error);
            // 只有错误、没有 onClose 时也必须走上层“断开”逻辑，否则永远不重连
            notifyClosedOnce(-1, error == null ? "网络异常" : String.valueOf(error.getMessage()));
        }
    }

    /** onClose / onError 只让上层收到一次断开事件 */
    private void notifyClosedOnce(int statusCode, String reason) {
        if (closeNotified) {
            return;
        }
        closeNotified = true;
        listener.onClosed(statusCode, reason);
    }
}
