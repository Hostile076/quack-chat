package io.quack.server.dispatch.handler;

import io.quack.common.dto.LoginReq;
import io.quack.common.dto.LoginResp;
import io.quack.common.model.ChatGroup;
import io.quack.common.model.ChatMessage;
import io.quack.common.model.ChatType;
import io.quack.common.model.User;
import io.quack.common.protocol.Cmd;
import io.quack.common.protocol.Envelope;
import io.quack.server.dispatch.ChatContext;
import io.quack.server.dispatch.CommandHandler;
import io.quack.server.service.FriendService;
import io.quack.server.service.GroupService;
import io.quack.server.service.PresenceService;
import io.quack.server.service.UserService;
import io.quack.server.store.MessageStore;
import io.quack.server.store.OfflineCursorStore;
import io.quack.server.support.BizException;
import io.quack.server.ws.ChatSession;
import io.quack.server.ws.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 登录：认证 -&gt; 绑定会话 -&gt; 广播上线 -&gt; 回包 -&gt; 按游标补发离线消息。
 *
 * <p>离线补发不再“每人每条存一份”，而是按会话记录“最后收到的 seq”，
 * 登录时把缺口一次性推过去；没推完的下次登录继续。
 */
@Component
public class LoginHandler implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(LoginHandler.class);
    /** 单次登录每个会话最多补发多少条，避免一次登录被历史消息淹没 */
    private static final int CATCH_UP_LIMIT = 100;

    private final UserService userService;
    private final SessionManager sessionManager;
    private final FriendService friendService;
    private final GroupService groupService;
    private final PresenceService presenceService;
    private final MessageStore messageStore;
    private final OfflineCursorStore cursorStore;
    private final io.quack.server.service.Metrics metrics;
    private final io.quack.server.service.AuthService authService;
    private final io.quack.server.service.RateLimiter rateLimiter;
    private final io.quack.server.service.UnreadService unreadService;

    public LoginHandler(UserService userService, SessionManager sessionManager, FriendService friendService,
                        GroupService groupService, PresenceService presenceService,
                        MessageStore messageStore, OfflineCursorStore cursorStore,
                        io.quack.server.service.Metrics metrics,
                        io.quack.server.service.AuthService authService,
                        io.quack.server.service.RateLimiter rateLimiter,
                        io.quack.server.service.UnreadService unreadService) {
        this.userService = userService;
        this.sessionManager = sessionManager;
        this.friendService = friendService;
        this.groupService = groupService;
        this.presenceService = presenceService;
        this.messageStore = messageStore;
        this.cursorStore = cursorStore;
        this.metrics = metrics;
        this.authService = authService;
        this.rateLimiter = rateLimiter;
        this.unreadService = unreadService;
    }

    @Override
    public String cmd() {
        return Cmd.LOGIN;
    }

    @Override
    public void handle(ChatContext ctx) {
        LoginReq req = ctx.payload(LoginReq.class);
        String ip = ctx.session().remoteAddress();
        if (rateLimiter.loginBlocked(ip)) {
            throw new BizException(429, "登录失败次数过多，请 5 分钟后再试");
        }
        Optional<User> authenticated = userService.authenticate(req.account(), req.password());
        if (authenticated.isEmpty()) {
            rateLimiter.recordLoginFailure(ip);
            throw BizException.unauthorized("账号或密码错误");
        }
        User user = authenticated.get();
        rateLimiter.clearLoginFailures(ip);

        String deviceId = req.deviceId() == null || req.deviceId().isBlank()
                ? "default" : req.deviceId().trim();
        sessionManager.bind(ctx.session(), user.id(), deviceId);
        presenceService.online(user.id());
        String token = authService.issue(user.id(), deviceId);

        ctx.reply(Cmd.LOGIN_RESP, new LoginResp(
                user,
                friendService.friendsOf(user.id()),
                groupService.groupsOf(user.id()),
                token,
                unreadService.allFor(user.id())));

        int pending = 0;
        for (String friendId : friendService.friendIdsOf(user.id())) {
            pending += catchUp(ctx.session(), user.id(), deviceId, ChatType.PRIVATE, friendId);
        }
        for (ChatGroup group : groupService.groupsOf(user.id())) {
            pending += catchUp(ctx.session(), user.id(), deviceId, ChatType.GROUP, group.id());
        }
        log.info("用户上线 qc={} nickname={} device={} 离线补发 {} 条",
                user.qcId(), user.nickname(), deviceId, pending);
        metrics.onMessagesDelivered(pending);
    }

    /** 把一台设备在一个会话里的缺口推给它；游标由客户端 RECVACK 推进（msg.recv） */
    private int catchUp(ChatSession session, String userId, String deviceId,
                        ChatType chatType, String peerId) {
        String key = OfflineCursorStore.conversationKey(chatType, userId, peerId);
        long since = cursorStore.cursor(userId, deviceId, key);
        List<ChatMessage> pending = messageStore.sync(chatType, userId, peerId, since, CATCH_UP_LIMIT);
        for (ChatMessage message : pending) {
            session.send(Envelope.of(Cmd.MSG_PUSH, 0, message));
        }
        return pending.size();
    }
}
