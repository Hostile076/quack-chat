package io.quack.server.service;

import io.quack.common.dto.ReadNotify;
import io.quack.common.model.ChatType;
import io.quack.common.protocol.Cmd;
import io.quack.common.protocol.Envelope;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 已读回执：记录每个会话里每个人读到了哪个序号。
 *
 * <p>位点以会话内 {@code seq} 为权威（{@code ts} 只为兼容旧客户端保留）。
 * 之前用时间戳，遇到同一毫秒多条消息或多实例时钟差就会漂移。
 */
@Service
public class ReadService {

    /** 会话 key -> (用户 -> 已读位点) */
    private final Map<String, Map<String, io.quack.server.store.PersistedState.ReadPos>> readAt =
            new ConcurrentHashMap<>();

    private final GroupService groupService;
    private final PushService pushService;

    public ReadService(GroupService groupService, PushService pushService) {
        this.groupService = groupService;
        this.pushService = pushService;
    }

    private String key(String chatType, String a, String b) {
        if ("GROUP".equals(chatType)) {
            return "GROUP:" + a;
        }
        return a.compareTo(b) <= 0 ? "PRIVATE:" + a + ":" + b : "PRIVATE:" + b + ":" + a;
    }

    /** 记录已读并通知对端/群成员 */
    public void markRead(String userId, String peerId, String chatType, long ts, long seq) {
        long when = ts > 0 ? ts : System.currentTimeMillis();
        String key = key(chatType, userId, peerId);
        readAt.computeIfAbsent(key, k -> new ConcurrentHashMap<>())
                .merge(userId, new io.quack.server.store.PersistedState.ReadPos(seq, when),
                        (oldPos, newPos) -> new io.quack.server.store.PersistedState.ReadPos(
                                Math.max(oldPos.seq(), newPos.seq()),
                                Math.max(oldPos.ts(), newPos.ts())));

        if ("GROUP".equals(chatType)) {
            ReadNotify notify = new ReadNotify("GROUP", peerId, userId, when, seq);
            groupService.forEachMember(peerId, member -> {
                if (!member.equals(userId)) {
                    pushService.forwardTransient(member,
                            Envelope.of(Cmd.MSG_READ_PUSH, 0, notify));
                }
            });
        } else {
            pushService.forwardTransient(peerId,
                    Envelope.of(Cmd.MSG_READ_PUSH, 0,
                            new ReadNotify("PRIVATE", userId, userId, when, seq)));
        }
    }

    /** 持久化：已读表 */
    public Map<String, Map<String, io.quack.server.store.PersistedState.ReadPos>> snapshot() {
        Map<String, Map<String, io.quack.server.store.PersistedState.ReadPos>> copy =
                new java.util.HashMap<>();
        readAt.forEach((key, value) -> copy.put(key, new java.util.HashMap<>(value)));
        return copy;
    }

    public void restore(Map<String, Map<String, io.quack.server.store.PersistedState.ReadPos>> state) {
        readAt.clear();
        if (state == null) {
            return;
        }
        state.forEach((key, value) -> {
            if (key != null && value != null) {
                readAt.put(key, new ConcurrentHashMap<>(value));
            }
        });
    }

    /** 某人读到的序号（0 表示没读过） */
    public long readSeq(String chatType, String a, String b, String readerId) {
        var pos = readAt.getOrDefault(key(chatType, a, b), Map.of()).get(readerId);
        return pos == null ? 0 : pos.seq();
    }

    /** 某人读到的时间点（兼容字段） */
    public long readTs(String chatType, String a, String b, String readerId) {
        var pos = readAt.getOrDefault(key(chatType, a, b), Map.of()).get(readerId);
        return pos == null ? 0 : pos.ts();
    }
}
