package io.quack.server.ws;

import io.quack.common.protocol.Envelope;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在线会话表：userId + deviceId &lt;-&gt; 连接 的映射。
 *
 * <p><b>这是单机版本的“路由表”。</b> 集群化时它的位置会被 Redis 取代：
 * 存 {@code userId/deviceId -> gatewayNodeId}，网关之间用消息队列 / Redis pub/sub 转发。
 *
 * <p>多端策略：同一账号允许多个设备同时在线；**同一设备**再登录会把旧连接踢掉（4001）。
 * 登录请求里没带 deviceId 的旧客户端按 {@code default} 设备处理，行为与历史版本一致。
 */
@Component
public class SessionManager {

    /** 连接断开的结果：谁下线了、是不是这个账号的最后一台设备 */
    public record CloseResult(String userId, boolean lastDevice) {
    }

    private final Map<String, ChatSession> bySessionId = new ConcurrentHashMap<>();
    /** userId -> (deviceId -> 连接) */
    private final Map<String, Map<String, ChatSession>> byUserDevices = new ConcurrentHashMap<>();

    /** 连接建立时调用：此时还没有身份 */
    public ChatSession open(WebSocketSession session) {
        ChatSession cs = new ChatSession(session);
        bySessionId.put(cs.id(), cs);
        return cs;
    }

    public ChatSession bySessionId(String sessionId) {
        return bySessionId.get(sessionId);
    }

    /**
     * 登录成功后绑定身份。
     *
     * @return 被同设备新连接顶下线的旧会话，没有则为 null
     */
    public ChatSession bind(ChatSession session, String userId, String deviceId) {
        String normalizedDevice = deviceId == null || deviceId.isBlank() ? "default" : deviceId.trim();
        // 同一条连接先登录 A、又登录 B 时，旧映射必须清掉，否则会“幽灵在线”
        String previousUser = session.userId();
        String previousDevice = session.deviceId();
        if (previousUser != null) {
            removeMapping(previousUser, previousDevice, session);
        }
        session.bind(userId, normalizedDevice);
        Map<String, ChatSession> devices =
                byUserDevices.computeIfAbsent(userId, k -> new ConcurrentHashMap<>());
        ChatSession previous = devices.put(normalizedDevice, session);
        if (previous != null && previous != session) {
            previous.close(4001, "账号在当前设备重新登录");
            return previous;
        }
        return null;
    }

    /**
     * 连接断开时调用。
     *
     * @return 真正下线的身份；若该连接已被同设备新连接取代则返回 null
     */
    public CloseResult close(String sessionId) {
        ChatSession session = bySessionId.remove(sessionId);
        if (session == null) {
            return null;
        }
        String userId = session.userId();
        if (userId == null) {
            return null;
        }
        boolean wasActive = removeMapping(userId, session.deviceId(), session);
        if (!wasActive) {
            return null;
        }
        Map<String, ChatSession> devices = byUserDevices.get(userId);
        boolean lastDevice = devices == null || devices.isEmpty();
        return new CloseResult(userId, lastDevice);
    }

    private boolean removeMapping(String userId, String deviceId, ChatSession session) {
        Map<String, ChatSession> devices = byUserDevices.get(userId);
        if (devices == null) {
            return false;
        }
        boolean removed = devices.remove(deviceId, session);
        if (removed && devices.isEmpty()) {
            byUserDevices.remove(userId, devices);
        }
        return removed;
    }

    public boolean isOnline(String userId) {
        Map<String, ChatSession> devices = byUserDevices.get(userId);
        return devices != null && devices.values().stream().anyMatch(ChatSession::isOpen);
    }

    /** 某账号当前在线的所有设备连接 */
    public Collection<ChatSession> devicesOf(String userId) {
        Map<String, ChatSession> devices = byUserDevices.get(userId);
        return devices == null ? List.of() : new ArrayList<>(devices.values());
    }

    /** 给某个账号的所有设备推送 */
    public void sendTo(String userId, Envelope envelope) {
        for (ChatSession session : devicesOf(userId)) {
            if (session.isOpen()) {
                session.send(envelope);
            }
        }
    }

    /** 给指定设备推送 */
    public void sendToDevice(String userId, String deviceId, Envelope envelope) {
        ChatSession session = byUserDevices.getOrDefault(userId, Map.of()).get(deviceId);
        if (session != null && session.isOpen()) {
            session.send(envelope);
        }
    }

    public void sendToAny(Collection<String> userIds, Envelope envelope) {
        for (String userId : userIds) {
            sendTo(userId, envelope);
        }
    }

    public Set<String> onlineUserIds() {
        return byUserDevices.keySet();
    }

    /** 在线连接数（一台设备算一个连接） */
    public int onlineCount() {
        return bySessionId.size();
    }

    /** 在线账号数 */
    public int onlineUserCount() {
        return byUserDevices.size();
    }

    /** 当前所有连接（用于服务端广播公告、强制下线等） */
    public Collection<ChatSession> allSessions() {
        return bySessionId.values();
    }
}
