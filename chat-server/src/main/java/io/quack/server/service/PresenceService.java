package io.quack.server.service;

import io.quack.common.dto.PresenceNotify;
import io.quack.common.protocol.Cmd;
import io.quack.common.protocol.Envelope;
import io.quack.server.ws.SessionManager;
import org.springframework.stereotype.Service;

/**
 * 在线状态广播。
 *
 * <p>只在好友关系内扩散：A 上线，只通知 A 的好友，不做全服广播。
 * （QQ 还有“隐身/忙碌”，那是在这层加一个状态枚举的扩展点。）
 */
@Service
public class PresenceService {

    private final FriendService friendService;
    private final SessionManager sessionManager;

    public PresenceService(FriendService friendService, SessionManager sessionManager) {
        this.friendService = friendService;
        this.sessionManager = sessionManager;
    }

    public void online(String userId) {
        broadcast(userId, true);
    }

    public void offline(String userId) {
        broadcast(userId, false);
    }

    private void broadcast(String userId, boolean online) {
        Envelope envelope = Envelope.of(Cmd.PRESENCE, 0, new PresenceNotify(userId, online));
        for (String friendId : friendService.friendIdsOf(userId)) {
            sessionManager.sendTo(friendId, envelope);
        }
    }
}
