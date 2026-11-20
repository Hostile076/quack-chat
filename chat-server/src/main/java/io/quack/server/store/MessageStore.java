package io.quack.server.store;

import io.quack.common.model.ChatMessage;
import io.quack.common.model.ChatType;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 消息存储（内存环形缓冲 + 每会话自增 seq）。
 *
 * <p>会话内顺序、增量同步、已读位点全部以 {@code seq} 为准，时间戳只用于展示。
 * 每会话保留最近 500 条；更早的消息由持久化层（SQLite）兜底，重启后恢复。
 *
 * <p>会话 key 的设计要点：私聊必须做到“双向同一个 key”，
 * 否则 A 发给 B 和 B 发给 A 会落进两个会话，历史记录就对不上了。
 */
@Component
public class MessageStore {

    private static final int MAX_PER_CONVERSATION = 500;
    /** clientMsgId 去重表的上限，按最久未用淘汰（只是防止无限增长） */
    private static final int MAX_CLIENT_KEYS = 20_000;

    private final Map<String, Deque<ChatMessage>> conversations = new ConcurrentHashMap<>();
    /** 会话 key -> 已分配的最大 seq */
    private final Map<String, AtomicLong> sequences = new ConcurrentHashMap<>();
    /** 消息 id -> 消息，供“精华消息”等按 id 引用 */
    private final Map<String, ChatMessage> byId = new ConcurrentHashMap<>();
    /** "发送者|clientMsgId" -> 消息：网络抖动重发时用来做幂等，避免同一条消息落两遍 */
    private final Map<String, ChatMessage> byClientKey = Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ChatMessage> eldest) {
                    return size() > MAX_CLIENT_KEYS;
                }
            });
    /** 被撤回修改过的消息 id：持久化时需要重写这些行（INSERT OR REPLACE） */
    private final java.util.Set<String> dirtyIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 保存消息并分配会话内序号。
     *
     * @return 带 seq 的新消息（ChatMessage 是不可变 record，所以返回新对象）
     */
    public ChatMessage save(ChatMessage message) {
        String key = message.conversationKey();
        long seq = sequences.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
        ChatMessage saved = withSeq(message, seq);
        append(saved);
        return saved;
    }

    /** 同一个发送者的同一个 clientMsgId 是否已经落过库（幂等重发） */
    public java.util.Optional<ChatMessage> byClientKey(String from, String clientMsgId) {
        if (from == null || clientMsgId == null || clientMsgId.isBlank()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(byClientKey.get(from + "|" + clientMsgId));
    }

    public java.util.Optional<ChatMessage> byId(String id) {
        return java.util.Optional.ofNullable(byId.get(id));
    }

    /**
     * 撤回消息：只能撤自己发的，且不超过时间窗口。
     *
     * <p>消息本身不删除（seq 空洞会让同步变复杂），而是替换成一条 SYSTEM 消息；
     * 历史里的其它消息不受影响。
     */
    public java.util.Optional<ChatMessage> recall(String messageId, String fromId, long maxAgeMs) {
        ChatMessage message = byId.get(messageId);
        if (message == null || fromId == null || !fromId.equals(message.from())) {
            return java.util.Optional.empty();
        }
        if (message.msgType() == io.quack.common.model.MsgType.SYSTEM) {
            // 已经撤回过，不能重复撤
            return java.util.Optional.empty();
        }
        if (maxAgeMs > 0 && System.currentTimeMillis() - message.ts() > maxAgeMs) {
            return java.util.Optional.empty();
        }
        ChatMessage recalled = new ChatMessage(message.id(), message.clientMsgId(), message.from(),
                message.fromName(), message.to(), message.chatType(), io.quack.common.model.MsgType.SYSTEM,
                "「消息已撤回」", message.ts(), message.fromAvatar(), message.seq(), false);
        byId.put(recalled.id(), recalled);
        if (recalled.clientMsgId() != null) {
            byClientKey.put(recalled.from() + "|" + recalled.clientMsgId(), recalled);
        }
        dirtyIds.add(recalled.id());
        return java.util.Optional.of(recalled);
    }

    /**
     * 编辑消息：只能改自己的文本消息。
     *
     * @return 编辑后的消息；不存在/不是本人/非文本时为空
     */
    public java.util.Optional<ChatMessage> edit(String messageId, String fromId, String content) {
        ChatMessage message = byId.get(messageId);
        if (message == null || fromId == null || !fromId.equals(message.from())) {
            return java.util.Optional.empty();
        }
        if (message.msgType() != io.quack.common.model.MsgType.TEXT) {
            return java.util.Optional.empty();
        }
        if (content == null || content.isBlank()) {
            return java.util.Optional.empty();
        }
        String trimmed = content.trim();
        if (trimmed.length() > 2000) {
            trimmed = trimmed.substring(0, 2000);
        }
        ChatMessage edited = new ChatMessage(message.id(), message.clientMsgId(), message.from(),
                message.fromName(), message.to(), message.chatType(), message.msgType(),
                trimmed, message.ts(), message.fromAvatar(), message.seq(), true);
        byId.put(edited.id(), edited);
        if (edited.clientMsgId() != null) {
            byClientKey.put(edited.from() + "|" + edited.clientMsgId(), edited);
        }
        dirtyIds.add(edited.id());
        return java.util.Optional.of(edited);
    }

    /** 持久化：被修改过（撤回）的消息 */
    public java.util.List<ChatMessage> dirtyMessages() {
        java.util.List<ChatMessage> result = new ArrayList<>();
        for (String id : dirtyIds) {
            ChatMessage message = byId.get(id);
            if (message != null) {
                result.add(message);
            }
        }
        return result;
    }

    /** 写库成功后清理脏标记 */
    public void clearDirty(java.util.Collection<String> ids) {
        dirtyIds.removeAll(ids);
    }

    /** 从 byId 取最新副本（撤回后 deque 里还留着旧对象） */
    private ChatMessage current(ChatMessage message) {
        ChatMessage latest = byId.get(message.id());
        return latest == null ? message : latest;
    }

    /** 会话当前最大 seq（没有消息时为 0） */
    public long latestSeq(ChatType chatType, String userId, String peerId) {
        return sequences.getOrDefault(keyOf(chatType, userId, peerId), new AtomicLong()).get();
    }

    /**
     * 增量同步：返回 seq > sinceSeq 的消息（升序，最多 limit 条）。
     *
     * <p>这是断线重连、换设备、重装客户端的统一补洞入口。
     */
    public List<ChatMessage> sync(ChatType chatType, String userId, String peerId,
                                  long sinceSeq, int limit) {
        Deque<ChatMessage> deque = conversations.get(keyOf(chatType, userId, peerId));
        if (deque == null) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(limit, 500));
        long from = Math.max(0, sinceSeq);
        List<ChatMessage> result = new ArrayList<>(safeLimit);
        synchronized (deque) {
            if (from <= 0) {
                // 首次打开会话：给“最近 N 条”，而不是最早的 N 条
                java.util.Iterator<ChatMessage> it = deque.descendingIterator();
                while (it.hasNext() && result.size() < safeLimit) {
                    result.add(current(it.next()));
                }
                Collections.reverse(result);
                return result;
            }
            for (ChatMessage message : deque) {
                if (message.seq() > from) {
                    result.add(current(message));
                    if (result.size() >= safeLimit) {
                        break;
                    }
                }
            }
        }
        return result;
    }

    /**
     * 找出“时间戳 <= ts 的最后一条消息”的 seq，用于兼容只传 ts 的旧客户端。
     */
    public long seqAtOrBefore(ChatType chatType, String userId, String peerId, long ts) {
        Deque<ChatMessage> deque = conversations.get(keyOf(chatType, userId, peerId));
        if (deque == null || ts <= 0) {
            return 0;
        }
        synchronized (deque) {
            java.util.Iterator<ChatMessage> it = deque.descendingIterator();
            while (it.hasNext()) {
                ChatMessage message = it.next();
                if (message.ts() <= ts) {
                    return message.seq();
                }
            }
        }
        return 0;
    }

    /**
     * 拉取历史消息（时间正序返回）。
     *
     * @param beforeTs 只取早于该时间戳的消息；0 表示不限
     */
    public List<ChatMessage> history(String userId, ChatType chatType, String peerId,
                                     long beforeTs, int limit) {
        String key = keyOf(chatType, userId, peerId);
        Deque<ChatMessage> deque = conversations.get(key);
        if (deque == null) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(limit, 200));
        List<ChatMessage> result = new ArrayList<>(safeLimit);
        synchronized (deque) {
            java.util.Iterator<ChatMessage> it = deque.descendingIterator();
            while (it.hasNext() && result.size() < safeLimit) {
                ChatMessage m = it.next();
                if (beforeTs <= 0 || m.ts() < beforeTs) {
                    result.add(current(m));
                }
            }
        }
        Collections.reverse(result);
        return result;
    }

    public int size(ChatType chatType, String a, String b) {
        Deque<ChatMessage> deque = conversations.get(conversationKey(chatType, a, b));
        if (deque == null) {
            return 0;
        }
        synchronized (deque) {
            return deque.size();
        }
    }

    /** 持久化：所有消息 */
    public List<ChatMessage> snapshot() {
        return new ArrayList<>(byId.values());
    }

    /** 当前保留的消息条数（指标用，避免为了计数复制整个列表） */
    public int count() {
        return byId.size();
    }

    /** 持久化：恢复（会先清空）。旧数据没有 seq 时按时间顺序补一个。 */
    public void restore(List<ChatMessage> messages) {
        if (messages == null) {
            return;
        }
        conversations.clear();
        byId.clear();
        byClientKey.clear();
        dirtyIds.clear();
        sequences.clear();
        List<ChatMessage> ordered = new ArrayList<>(messages.stream()
                .filter(message -> message != null && message.id() != null)
                .toList());
        ordered.sort(Comparator
                .comparing((ChatMessage m) -> m.conversationKey())
                .thenComparingLong(ChatMessage::seq)
                .thenComparingLong(ChatMessage::ts)
                .thenComparing(ChatMessage::id));
        for (ChatMessage message : ordered) {
            String key = message.conversationKey();
            AtomicLong sequence = sequences.computeIfAbsent(key, k -> new AtomicLong());
            long seq = message.seq() > 0 ? message.seq() : sequence.get() + 1;
            sequence.accumulateAndGet(seq, Math::max);
            append(seq == message.seq() ? message : withSeq(message, seq));
        }
    }

    public static String privateKey(String a, String b) {
        // 与 ChatMessage.conversationKey()、离线游标、SQLite 的 conv_key 保持同一格式
        return a.compareTo(b) <= 0 ? "PRIVATE:" + a + ":" + b : "PRIVATE:" + b + ":" + a;
    }

    public static String groupKey(String groupId) {
        return "GROUP:" + groupId;
    }

    private static String conversationKey(ChatType chatType, String a, String b) {
        return chatType == ChatType.PRIVATE ? privateKey(a, b) : groupKey(b);
    }

    private static String keyOf(ChatType chatType, String userId, String peerId) {
        return chatType == ChatType.PRIVATE ? privateKey(userId, peerId) : groupKey(peerId);
    }

    private static ChatMessage withSeq(ChatMessage message, long seq) {
        return new ChatMessage(message.id(), message.clientMsgId(), message.from(), message.fromName(),
                message.to(), message.chatType(), message.msgType(), message.content(),
                message.ts(), message.fromAvatar(), seq, message.edited());
    }

    private void append(ChatMessage message) {
        String key = message.conversationKey();
        Deque<ChatMessage> deque = conversations.computeIfAbsent(key, k -> new ArrayDeque<>());
        byId.put(message.id(), message);
        if (message.clientMsgId() != null && !message.clientMsgId().isBlank()) {
            byClientKey.put(message.from() + "|" + message.clientMsgId(), message);
        }
        synchronized (deque) {
            deque.addLast(message);
            while (deque.size() > MAX_PER_CONVERSATION) {
                deque.pollFirst();
            }
        }
    }
}
