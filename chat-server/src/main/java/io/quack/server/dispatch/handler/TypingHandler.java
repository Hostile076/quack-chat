package io.quack.server.dispatch.handler;

import io.quack.common.dto.TypingNotify;
import io.quack.common.model.ChatType;
import io.quack.common.protocol.Cmd;
import io.quack.common.protocol.Envelope;
import io.quack.server.dispatch.ChatContext;
import io.quack.server.dispatch.CommandHandler;
import io.quack.server.service.GroupService;
import io.quack.server.service.PushService;
import io.quack.server.service.UserService;
import org.springframework.stereotype.Component;

/**
 * “正在输入”状态转发。
 *
 * <p>这类消息语义上是**瞬时**的：离线就没有意义，所以不做存储、不做重试，
 * 在线才推，推失败直接丢弃。
 */
@Component
public class TypingHandler implements CommandHandler {

    private final UserService userService;
    private final GroupService groupService;
    private final PushService pushService;

    public TypingHandler(UserService userService, GroupService groupService, PushService pushService) {
        this.userService = userService;
        this.groupService = groupService;
        this.pushService = pushService;
    }

    @Override
    public String cmd() {
        return Cmd.TYPING;
    }

    @Override
    public void handle(ChatContext ctx) {
        TypingNotify req = ctx.payload(TypingNotify.class);
        String from = ctx.userId();
        ChatType chatType = req.chatType() == null ? ChatType.PRIVATE : req.chatType();
        TypingNotify notify = new TypingNotify(from, userService.nicknameOf(from), req.to(), chatType);
        Envelope envelope = Envelope.of(Cmd.TYPING, 0, notify);

        if (chatType == ChatType.PRIVATE) {
            pushService.forwardTransient(req.to(), envelope);
        } else {
            // 群「正在输入」必须先确认自己在群里，否则任何人可以拿群号
            // 给全群刷“正在输入”。
            if (req.to() == null || !groupService.contains(req.to(), from)) {
                return;
            }
            for (String memberId : groupService.membersOf(req.to())) {
                if (!memberId.equals(from)) {
                    pushService.forwardTransient(memberId, envelope);
                }
            }
        }
    }
}
