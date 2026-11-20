package io.quack.server.dispatch.handler;

import io.quack.common.dto.FriendRequestsResp;
import io.quack.common.protocol.Cmd;
import io.quack.server.dispatch.ChatContext;
import io.quack.server.dispatch.CommandHandler;
import io.quack.server.service.FriendRequestService;
import org.springframework.stereotype.Component;

/** 拉取好友申请列表：收到的 + 我发出的 */
@Component
public class FriendRequestsHandler implements CommandHandler {

    private final FriendRequestService friendRequestService;

    public FriendRequestsHandler(FriendRequestService friendRequestService) {
        this.friendRequestService = friendRequestService;
    }

    @Override
    public String cmd() {
        return Cmd.FRIEND_REQUESTS;
    }

    @Override
    public void handle(ChatContext ctx) {
        String me = ctx.userId();
        ctx.reply(Cmd.FRIEND_REQUESTS_RESP,
                new FriendRequestsResp(friendRequestService.incoming(me), friendRequestService.outgoing(me)));
    }
}
