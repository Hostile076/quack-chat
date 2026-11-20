package io.quack.server.ws;

import io.quack.common.protocol.Envelope;
import io.quack.common.protocol.JsonCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;

/**
 * 一条已建立的连接。
 *
 * <p>把 Spring 的 {@link WebSocketSession} 包一层，目的是：
 * <ol>
 *   <li>给连接附加“身份”（userId），避免业务代码到处传 session + userId 两个参数；</li>
 *   <li>把 sendMessage 的线程安全与异常吞掉，调用方不需要处理 IOException；</li>
 *   <li>预留 lastActiveAt，后续做闲置连接清理。</li>
 * </ol>
 */
public class ChatSession {

    private static final Logger log = LoggerFactory.getLogger(ChatSession.class);

    private final WebSocketSession session;
    private volatile String userId;
    private volatile String deviceId = "default";
    private volatile long lastActiveAt = System.currentTimeMillis();

    public ChatSession(WebSocketSession session) {
        this.session = session;
    }

    public String id() {
        return session.getId();
    }

    public String userId() {
        return userId;
    }

    public String deviceId() {
        return deviceId;
    }

    public void bind(String userId, String deviceId) {
        this.userId = userId;
        if (deviceId != null && !deviceId.isBlank()) {
            this.deviceId = deviceId;
        }
    }

    public boolean isAuthed() {
        return userId != null;
    }

    public boolean isOpen() {
        return session.isOpen();
    }

    /** 客户端 IP，用于登录/消息限流（拿不到就返回 unknown） */
    public String remoteAddress() {
        try {
            var address = session.getRemoteAddress();
            return address == null ? "unknown" : address.getAddress().getHostAddress();
        } catch (RuntimeException error) {
            return "unknown";
        }
    }

    public long lastActiveAt() {
        return lastActiveAt;
    }

    public void touch() {
        this.lastActiveAt = System.currentTimeMillis();
    }

    /**
     * 发送一帧。
     *
     * <p>注意：Spring 的 WebSocketSession 不是线程安全的（Tomcat 实现下并发写会抛
     * IllegalStateException: TEXT_PARTIAL_WRITING），而消息推送可能来自任意业务线程，
     * 所以这里必须串行化。高并发场景应改为每个连接一条发送队列 + 单写线程，
     * 或者换 Netty 的 Channel.writeAndFlush（本身线程安全）。
     */
    public void send(Envelope envelope) {
        if (!session.isOpen()) {
            return;
        }
        String text = JsonCodec.encode(envelope);
        synchronized (this) {
            try {
                session.sendMessage(new TextMessage(text));
            } catch (IOException | IllegalStateException e) {
                log.warn("发送失败 session={} user={} cmd={}: {}", id(), userId, envelope.cmd(), e.getMessage());
            }
        }
    }

    /** 服务端主动断开（如异地登录踢下线） */
    public void close(int code, String reason) {
        try {
            if (session.isOpen()) {
                session.close(new CloseStatus(code, reason));
            }
        } catch (IOException e) {
            log.debug("关闭连接失败 session={}: {}", id(), e.getMessage());
        }
    }
}
