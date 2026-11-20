package io.quack.server.service;

import io.quack.common.model.ChatMessage;
import io.quack.server.store.MessageStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 群精华消息：只存消息 id，取的时候回查内容 */
@Service
public class EssenceService {

    private final Map<String, Set<String>> byGroup = new ConcurrentHashMap<>();
    private final MessageStore messageStore;

    public EssenceService(MessageStore messageStore) {
        this.messageStore = messageStore;
    }

    public void add(String groupId, String messageId) {
        byGroup.computeIfAbsent(groupId, k -> ConcurrentHashMap.newKeySet()).add(messageId);
    }

    public void remove(String groupId, String messageId) {
        Set<String> set = byGroup.get(groupId);
        if (set != null) {
            set.remove(messageId);
        }
    }

    public List<ChatMessage> list(String groupId) {
        Set<String> ids = byGroup.getOrDefault(groupId, Set.of());
        List<ChatMessage> result = new ArrayList<>();
        for (String id : ids) {
            messageStore.byId(id).ifPresent(result::add);
        }
        result.sort(java.util.Comparator.comparingLong(ChatMessage::ts));
        return result;
    }

    public boolean contains(String groupId, String messageId) {
        return byGroup.getOrDefault(groupId, Set.of()).contains(messageId);
    }

    /** 用于“添加精华”时判断消息是不是这个群的 */
    public boolean belongsTo(ChatMessage message, String groupId) {
        return message != null && groupId.equals(message.to());
    }

    public List<String> ids(String groupId) {
        return new ArrayList<>(byGroup.getOrDefault(groupId, new LinkedHashSet<>()));
    }
}
