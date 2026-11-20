package io.quack.server.dispatch.handler;

import io.quack.common.dto.FriendRemarkReq;
import io.quack.common.model.Friend;
import io.quack.common.model.User;
import io.quack.common.protocol.Cmd;
import io.quack.server.dispatch.ChatContext;
import io.quack.server.dispatch.CommandHandler;
import io.quack.server.service.FriendService;
import io.quack.server.service.PushService;
import io.quack.server.service.UserService;
import io.quack.server.support.BizException;
import org.springframework.stereotype.Component;

import java.util.List;

/** 设置好友备注：备注只应用于「我」这一侧，对方无感 */
@Component
public class FriendRemarkHandler implements CommandHandler {

    private final FriendService friendService;
    private final UserService userService;
    private final PushService pushService;

    public FriendRemarkHandler(FriendService friendService, UserService userService,
                               PushService pushService) {
        this.friendService = friendService;
        this.userService = userService;
        this.pushService = pushService;
    }

    @Override
    public String cmd() {
        return Cmd.FRIEND_REMARK;
    }

    @Override
    public void handle(ChatContext ctx) {
        FriendRemarkReq req = ctx.payload(FriendRemarkReq.class);
        if (req.friendId() == null || req.friendId().isBlank()) {
            throw BizException.badRequest("缺少好友 ID");
        }
        friendService.setRemark(ctx.userId(), req.friendId(), req.remark());
        Friend friend = friendService.viewOf(ctx.userId(), req.friendId())
                .orElseThrow(() -> new BizException(404, "好友不存在"));
        ctx.reply(Cmd.FRIEND_REMARK_RESP, friend);
        // 多端同步：给本人推一份（其它端也能改备注名）
        pushService.forwardTransient(ctx.userId(),
                io.quack.common.protocol.Envelope.of(Cmd.FRIEND_REMARK_PUSH, 0, friend));
    }
}