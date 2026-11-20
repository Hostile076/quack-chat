package io.quack.server.service;

import io.quack.common.model.ChatMessage;
import io.quack.common.model.ChatType;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端未读数：每个用户在每个会话里有多少条没读。
 *
 * <p>客户端也会本地累加，但换设备/重装后就丢了；服务端这份是权威值，
 * 登录时下发、已读时清零，并推给同一账号的其它设备。
 */
@Service
public class UnreadService {

    /** userId -> (conversationKey -> 未读数) */
    private final Map<String, Map<String, Integer>> unread = new ConcurrentHashMap<>();
    private volatile boolean dirty;

    public void incrementForMessage(ChatMessage message) {
        if (message == null) {
            return;
        }
        if (message.chatType() == ChatType.PRIVATE) {
            increment(message.to(), message.conversationKey(), 1);
        }
        // 群聊的接收者由调用方逐个传入（需要成员列表），见 incrementForGroup
    }

    public void incrementForGroup(String userId, ChatMessage message) {
        if (userId != null && !userId.equals(message.from())) {
            increment(userId, message.conversationKey(), 1);
        }
    }

    private void increment(String userId, String conversationKey, int delta) {
        if (userId == null || conversationKey == null) {
            return;
        }
        unread.computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
                .merge(conversationKey, delta, Integer::sum);
        dirty = true;
    }

    /** 撤回一条还没读的消息时，未读数要减回去，避免留下“幽灵未读” */
    public void decrement(String userId, String conversationKey) {
        if (userId == null || conversationKey == null) {
            return;
        }
        Map<String, Integer> map = unread.get(userId);
        if (map == null) {
            return;
        }
        Integer current = map.get(conversationKey);
        if (current == null || current <= 0) {
            return;
        }
        if (current == 1) {
            map.remove(conversationKey);
        } else {
            map.put(conversationKey, current - 1);
        }
        dirty = true;
    }

    /** 已读到 seq 后，把未读数设置为“这个会话里还剩多少条” */
    public void setUnread(String userId, String conversationKey, int count) {
        if (userId == null || conversationKey == null) {
            return;
        }
        Map<String, Integer> map = unread.computeIfAbsent(userId, k -> new ConcurrentHashMap<>());
        int value = Math.max(0, count);
        if (value == 0) {
            map.remove(conversationKey);
        } else {
            map.put(conversationKey, value);
        }
        dirty = true;
    }

    public int count(String userId, String conversationKey) {
        return unread.getOrDefault(userId, Map.of()).getOrDefault(conversationKey, 0);
    }

    public Map<String, Integer> allFor(String userId) {
        return new java.util.HashMap<>(unread.getOrDefault(userId, Map.of()));
    }

    public Map<String, Map<String, Integer>> snapshot() {
        Map<String, Map<String, Integer>> copy = new java.util.HashMap<>();
        unread.forEach((userId, value) -> copy.put(userId, new java.util.HashMap<>(value)));
        return copy;
    }

    public void restore(Map<String, Map<String, Integer>> state) {
        unread.clear();
        if (state == null) {
            return;
        }
        state.forEach((userId, value) -> {
            if (userId != null && value != null) {
                unread.put(userId, new java.util.concurrent.ConcurrentHashMap<>(value));
            }
        });
        dirty = false;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void consumeDirty() {
        dirty = false;
    }
}
