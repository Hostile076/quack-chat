package io.quack.server.dispatch.handler;

import io.quack.common.dto.FriendRequestReq;
import io.quack.common.dto.FriendRequestResp;
import io.quack.common.model.FriendRequest;
import io.quack.common.protocol.Cmd;
import io.quack.common.protocol.Envelope;
import io.quack.server.dispatch.ChatContext;
import io.quack.server.dispatch.CommandHandler;
import io.quack.server.service.FriendRequestService;
import io.quack.server.service.PushService;
import io.quack.server.service.UserService;
import io.quack.server.support.BizException;
import org.springframework.stereotype.Component;

/**
 * 发起好友申请。
 *
 * <p>注意：这里**不会**直接建立好友关系，只写一条待处理申请，
 * 并通过 {@code friend.request.push} 实时推给对方。
 */
@Component
public class FriendRequestSendHandler implements CommandHandler {

    private final UserService userService;
    private final FriendRequestService friendRequestService;
    private final PushService pushService;

    public FriendRequestSendHandler(UserService userService, FriendRequestService friendRequestService,
                                    PushService pushService) {
        this.userService = userService;
        this.friendRequestService = friendRequestService;
        this.pushService = pushService;
    }

    @Override
    public String cmd() {
        return Cmd.FRIEND_REQUEST;
    }

    @Override
    public void handle(ChatContext ctx) {
        FriendRequestReq req = ctx.payload(FriendRequestReq.class);
        String me = ctx.userId();
        String toId = req.toId() == null ? "" : req.toId().trim();
        if (toId.isEmpty()) {
            throw BizException.badRequest("请选择要添加的用户");
        }
        var target = userService.byId(toId).orElseThrow(() -> new BizException(404, "用户不存在：" + toId));
        var self = userService.byId(me).orElseThrow(() -> BizException.unauthorized("账号状态异常"));

        FriendRequest request = friendRequestService.create(self, target.id(), req.message(), req.remark());

        ctx.reply(Cmd.FRIEND_REQUEST_RESP, new FriendRequestResp(request));
        pushService.forwardTransient(target.id(), Envelope.of(Cmd.FRIEND_REQUEST_PUSH, 0, request));
    }
}
