package io.quack.server.store;

import io.quack.common.model.ChatType;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 离线游标：每台设备在每个会话里“已经确认收到”的最大 seq。
 *
 * <p>这是对旧版“每人每条消息存一份离线副本”的替代：群消息只存一份，
 * 离线设备只记一个游标；上线时按游标一次性 sync 缺口。
 *
 * <p>按设备而不是按账号记录，才能真正支持多端：手机收到过不代表电脑也收到过。
 */
@Component
public class OfflineCursorStore {

    /** userId -> deviceId -> (conversationKey -> 已收到 seq) */
    private final Map<String, Map<String, Map<String, Long>>> cursors = new ConcurrentHashMap<>();
    private volatile boolean dirty;

    public long cursor(String userId, String deviceId, String conversationKey) {
        return cursors.getOrDefault(userId, Map.of())
                .getOrDefault(deviceId, Map.of())
                .getOrDefault(conversationKey, 0L);
    }

    /** 推进游标（只增不减） */
    public void advance(String userId, String deviceId, String conversationKey, long seq) {
        if (userId == null || conversationKey == null || seq <= 0) {
            return;
        }
        String device = deviceId == null || deviceId.isBlank() ? "default" : deviceId;
        Map<String, Long> map = cursors
                .computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(device, k -> new ConcurrentHashMap<>());
        Long previous = map.get(conversationKey);
        if (previous != null && previous >= seq) {
            return;
        }
        map.put(conversationKey, seq);
        dirty = true;
    }

    public Map<String, Long> cursorsOf(String userId, String deviceId) {
        return new java.util.HashMap<>(cursors.getOrDefault(userId, Map.of())
                .getOrDefault(deviceId, Map.of()));
    }

    public Map<String, Map<String, Map<String, Long>>> snapshot() {
        Map<String, Map<String, Map<String, Long>>> copy = new java.util.HashMap<>();
        cursors.forEach((userId, devices) -> {
            Map<String, Map<String, Long>> deviceCopy = new java.util.HashMap<>();
            devices.forEach((deviceId, values) -> deviceCopy.put(deviceId, new java.util.HashMap<>(values)));
            copy.put(userId, deviceCopy);
        });
        return copy;
    }

    public void restore(Map<String, Map<String, Map<String, Long>>> state) {
        cursors.clear();
        if (state == null) {
            return;
        }
        state.forEach((userId, devices) -> {
            if (userId == null || devices == null) {
                return;
            }
            Map<String, Map<String, Long>> deviceCopy = new java.util.concurrent.ConcurrentHashMap<>();
            devices.forEach((deviceId, values) -> {
                if (deviceId != null && values != null) {
                    deviceCopy.put(deviceId, new java.util.concurrent.ConcurrentHashMap<>(values));
                }
            });
            cursors.put(userId, deviceCopy);
        });
        dirty = false;
    }

    public boolean isDirty() {
        return dirty;
    }

    /** 提交成功后调用 */
    public void consumeDirty() {
        dirty = false;
    }

    /** 和消息一样的会话 key：私聊双向同一个 key */
    public static String conversationKey(ChatType chatType, String userId, String peerId) {
        if (chatType == ChatType.GROUP) {
            return "GROUP:" + peerId;
        }
        return userId.compareTo(peerId) <= 0
                ? "PRIVATE:" + userId + ":" + peerId
                : "PRIVATE:" + peerId + ":" + userId;
    }
}
