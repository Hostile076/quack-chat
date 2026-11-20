package io.quack.server.dispatch.handler;

import io.quack.common.dto.FriendHandleReq;
import io.quack.common.dto.FriendHandleResp;
import io.quack.common.model.Friend;
import io.quack.common.model.FriendRequest;
import io.quack.common.protocol.Cmd;
import io.quack.common.protocol.Envelope;
import io.quack.server.dispatch.ChatContext;
import io.quack.server.dispatch.CommandHandler;
import io.quack.server.service.FriendRequestService;
import io.quack.server.service.FriendService;
import io.quack.server.service.PushService;
import org.springframework.stereotype.Component;

/**
 * 同意 / 拒绝好友申请。
 *
 * <p>同意时：建立双向好友关系 → 回包给处理人（带对方好友信息）→ 推 {@code friend.added} 给申请人。
 * 拒绝时：只推 {@code friend.handled.push} 通知申请人结果。
 */
@Component
public class FriendHandleHandler implements CommandHandler {

    private final FriendRequestService friendRequestService;
    private final FriendService friendService;
    private final PushService pushService;

    public FriendHandleHandler(FriendRequestService friendRequestService, FriendService friendService,
                               PushService pushService) {
        this.friendRequestService = friendRequestService;
        this.friendService = friendService;
        this.pushService = pushService;
    }

    @Override
    public String cmd() {
        return Cmd.FRIEND_HANDLE;
    }

    @Override
    public void handle(ChatContext ctx) {
        FriendHandleReq req = ctx.payload(FriendHandleReq.class);
        String me = ctx.userId();
        FriendRequest pending = friendRequestService.requireForTarget(req.requestId(), me);
        FriendRequest done = friendRequestService.finish(pending, req.accept());

        Friend newFriend = null;
        if (req.accept()) {
            friendService.link(me, pending.fromId(), null, pending.remark());
            newFriend = friendService.viewOf(me, pending.fromId()).orElse(null);
            // 申请人那边也要立刻多出这个好友
            friendService.viewOf(pending.fromId(), me).ifPresent(reverse ->
                    pushService.forwardTransient(pending.fromId(),
                            Envelope.of(Cmd.FRIEND_ADDED, 0, reverse)));
        } else {
            pushService.forwardTransient(pending.fromId(),
                    Envelope.of(Cmd.FRIEND_HANDLED_PUSH, 0, done));
        }

        ctx.reply(Cmd.FRIEND_HANDLE_RESP, new FriendHandleResp(done, newFriend));
    }
}
