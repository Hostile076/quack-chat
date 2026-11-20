package io.quack.server.service;

import io.quack.common.dto.CallParticipant;
import io.quack.common.dto.CallParticipants;
import io.quack.common.model.CallSignal;
import io.quack.common.model.User;
import io.quack.common.protocol.Cmd;
import io.quack.common.protocol.Envelope;
import io.quack.server.support.BizException;
import io.quack.server.ws.SessionManager;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通话信令：既支持一对一，也支持群通话（多人接听）。
 *
 * <p>群通话只维护「谁接了」；音频由服务端转发给所有已接听者（没有混音）。
 * 真正的音视频会议需要 SFU/WebRTC，这里明确是局域网级的简化实现。
 */
@Service
public class CallService {

    /** callId → 通话 */
    private final Map<String, CallSignal> active = new ConcurrentHashMap<>();
    /** callId → 已接听成员（保持顺序） */
    private final Map<String, Map<String, CallParticipant>> participants = new ConcurrentHashMap<>();
    /** callId → 群号（一对一为空） */
    private final Map<String, String> groupOf = new ConcurrentHashMap<>();

    private final SessionManager sessionManager;
    private final PushService pushService;
    private final GroupService groupService;
    private final UserService userService;

    public CallService(SessionManager sessionManager, PushService pushService,
                       GroupService groupService, UserService userService) {
        this.sessionManager = sessionManager;
        this.pushService = pushService;
        this.groupService = groupService;
        this.userService = userService;
    }

    // ------------------------------------------------------------------
    // 发起
    // ------------------------------------------------------------------

    public CallSignal invite(User from, String toId) {
        if (toId == null || toId.isBlank()) {
            throw BizException.badRequest("缺少对方 QC 号");
        }
        if (toId.startsWith("q")) {
            return inviteGroup(from, toId);
        }
        if (toId.equals(from.id())) {
            throw BizException.badRequest("不能呼叫自己");
        }
        if (!sessionManager.isOnline(toId)) {
            throw BizException.badRequest("对方当前不在线");
        }
        CallSignal signal = new CallSignal(UUID.randomUUID().toString(), from.id(),
                from.displayName(), toId, CallSignal.INVITE, System.currentTimeMillis());
        active.put(signal.callId(), signal);
        registerParticipant(signal.callId(), from);
        pushService.forwardTransient(toId, Envelope.of(Cmd.CALL_INCOMING, 0, signal));
        broadcastParticipants(signal.callId());
        return signal;
    }

    /** 群通话：邀请所有在线成员 */
    private CallSignal inviteGroup(User from, String groupId) {
        if (!groupService.contains(groupId, from.id())) {
            throw BizException.forbidden("你不在该群");
        }
        List<String> members = new ArrayList<>(groupService.membersOf(groupId));
        long online = members.stream().filter(sessionManager::isOnline).count();
        if (online <= 1) {
            throw BizException.badRequest("群里目前没有其他人在线");
        }
        CallSignal signal = new CallSignal(UUID.randomUUID().toString(), from.id(),
                from.displayName(), groupId, CallSignal.INVITE, System.currentTimeMillis());
        active.put(signal.callId(), signal);
        groupOf.put(signal.callId(), groupId);
        registerParticipant(signal.callId(), from);

        for (String member : members) {
            if (!member.equals(from.id()) && sessionManager.isOnline(member)) {
                pushService.forwardTransient(member, Envelope.of(Cmd.CALL_INCOMING, 0, signal));
            }
        }
        broadcastParticipants(signal.callId());
        return signal;
    }

    // ------------------------------------------------------------------
    // 接听 / 拒绝 / 挂断
    // ------------------------------------------------------------------

    public CallSignal respond(String userId, String callId, String type) {
        CallSignal signal = active.get(callId);
        if (signal == null) {
            throw new BizException(404, "通话不存在或已结束");
        }
        boolean participant = userId.equals(signal.fromId()) || userId.equals(signal.toId());
        boolean groupCall = groupOf.containsKey(callId);
        boolean groupMember = groupCall && groupService.contains(groupOf.get(callId), userId);
        if (!participant && !groupMember) {
            throw BizException.forbidden("你不是这次通话的参与者");
        }

        CallSignal updated = new CallSignal(signal.callId(), signal.fromId(), signal.fromName(),
                signal.toId(), type, System.currentTimeMillis());

        switch (type) {
            case CallSignal.ACCEPT -> {
                userService.byId(userId).ifPresent(user -> registerParticipant(callId, user));
                pushService.forwardTransient(signal.fromId(),
                        Envelope.of(Cmd.CALL_ANSWERED, 0, updated));
                broadcastParticipants(callId);
            }
            case CallSignal.REJECT -> {
                // 群通话里某个人拒绝不影响其他人
                if (!groupCall) {
                    active.remove(callId);
                }
                pushService.forwardTransient(signal.fromId(),
                        Envelope.of(Cmd.CALL_REJECTED, 0, updated));
            }
            default -> {
                if (groupCall && !userId.equals(signal.fromId())) {
                    // 群通话：只有发起人挂断才结束整场，其他人只是离开
                    Map<String, CallParticipant> joined = participants.get(callId);
                    if (joined != null) {
                        joined.remove(userId);
                    }
                    broadcastParticipants(callId);
                    return updated;
                }
                active.remove(callId);
                participants.remove(callId);
                groupOf.remove(callId);
                String other = userId.equals(signal.fromId()) ? signal.toId() : signal.fromId();
                pushService.forwardTransient(other, Envelope.of(Cmd.CALL_ENDED, 0, updated));
                if (groupCall) {
                    for (String member : groupService.membersOf(signal.toId())) {
                        if (!member.equals(userId)) {
                            pushService.forwardTransient(member,
                                    Envelope.of(Cmd.CALL_ENDED, 0, updated));
                        }
                    }
                }
            }
        }
        return updated;
    }

    // ------------------------------------------------------------------
    // 音频：转发给所有已接听者
    // ------------------------------------------------------------------

    public void relayAudio(String userId, String callId, String data) {
        CallSignal signal = active.get(callId);
        if (signal == null) {
            throw new BizException(404, "通话已结束");
        }
        if (data == null || data.length() > 12_000) {
            return;
        }
        Map<String, CallParticipant> joined = participants.get(callId);
        // 只有已接听的人才能往这个通话里注音频；否则任何登录用户
        // 只要拿到 callId 就能往别人的通话里灌声音。
        if (joined == null || !joined.containsKey(userId)) {
            return;
        }
        List<String> targets;
        synchronized (joined) {
            targets = new ArrayList<>(joined.keySet());
        }
        for (String member : targets) {
            if (!member.equals(userId)) {
                pushService.forwardTransient(member,
                        Envelope.of(Cmd.CALL_AUDIO_PUSH, 0,
                                new io.quack.common.dto.CallAudioReq(callId, data)));
            }
        }
    }

    public boolean isActive(String callId) {
        return active.containsKey(callId);
    }

    // ------------------------------------------------------------------
    // 参与者
    // ------------------------------------------------------------------

    private void registerParticipant(String callId, User user) {
        // LinkedHashMap 保证头像墙顺序稳定，但迭代必须加锁（接听/挂断可能来自别的线程）
        participants.computeIfAbsent(callId, key -> java.util.Collections.synchronizedMap(new LinkedHashMap<>()))
                .put(user.id(), new CallParticipant(user.id(), user.displayName(), user.avatar()));
    }

    private void broadcastParticipants(String callId) {
        String groupId = groupOf.get(callId);
        Map<String, CallParticipant> joined = participants.getOrDefault(callId, Map.of());
        List<CallParticipant> snapshot;
        synchronized (joined) {
            snapshot = new ArrayList<>(joined.values());
        }
        CallParticipants payload = new CallParticipants(callId, groupId, snapshot);
        Envelope envelope = Envelope.of(Cmd.CALL_PARTICIPANTS, 0, payload);
        if (groupId != null) {
            for (String member : groupService.membersOf(groupId)) {
                pushService.forwardTransient(member, envelope);
            }
        } else {
            CallSignal signal = active.get(callId);
            if (signal != null) {
                pushService.forwardTransient(signal.fromId(), envelope);
                pushService.forwardTransient(signal.toId(), envelope);
            }
        }
    }
}
