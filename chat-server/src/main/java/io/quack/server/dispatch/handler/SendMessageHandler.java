package io.quack.server.dispatch.handler;

import io.quack.common.dto.SendMessageAck;
import io.quack.common.dto.SendMessageReq;
import io.quack.common.model.ChatType;
import io.quack.common.protocol.Cmd;
import io.quack.server.dispatch.ChatContext;
import io.quack.server.dispatch.CommandHandler;
import io.quack.server.service.FriendService;
import io.quack.server.service.MessageService;
import io.quack.server.service.UserService;
import io.quack.server.support.BizException;
import org.springframework.stereotype.Component;

/**
 * 发送消息（私聊 / 群聊统一入口）。
 *
 * <p>应答有两条路径：
 * <ul>
 *   <li>发给发送者：{@code msg.ack}，带服务端消息 id，用于把“发送中”变成“已送达”；</li>
 *   <li>发给接收者：{@code msg.push}，由 PushService 负责在线直推 / 离线入箱。</li>
 * </ul>
 */
@Component
public class SendMessageHandler implements CommandHandler {

    private final MessageService messageService;
    private final FriendService friendService;
    private final UserService userService;

    public SendMessageHandler(MessageService messageService, FriendService friendService, UserService userService) {
        this.messageService = messageService;
        this.friendService = friendService;
        this.userService = userService;
    }

    @Override
    public String cmd() {
        return Cmd.MSG_SEND;
    }

    @Override
    public void handle(ChatContext ctx) {
        SendMessageReq req = ctx.payload(SendMessageReq.class);
        String from = ctx.userId();

        if (req.to() == null || req.to().isBlank()) {
            throw BizException.badRequest("收件人不能为空");
        }
        // 私聊必须先是好友；群聊的成员校验在 MessageService 里做（那里有 GroupService）
        if (req.chatType() == ChatType.PRIVATE && !friendService.areFriends(from, req.to())) {
            throw BizException.forbidden("对方不是你的好友");
        }

        SendMessageAck ack = messageService.send(from, userService.nicknameOf(from), req,
                ctx.session().deviceId());
        ctx.reply(Cmd.MSG_ACK, ack);
    }
}
