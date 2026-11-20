package io.quack.server.dispatch.handler;

import io.quack.common.dto.EditReq;
import io.quack.common.model.ChatMessage;
import io.quack.common.protocol.Cmd;
import io.quack.server.dispatch.ChatContext;
import io.quack.server.dispatch.CommandHandler;
import io.quack.server.service.PushService;
import io.quack.server.store.MessageStore;
import io.quack.server.support.BizException;
import org.springframework.stereotype.Component;

/** 编辑消息：只能改自己发的文本消息 */
@Component
public class EditHandler implements CommandHandler {

    private final MessageStore messageStore;
    private final PushService pushService;

    public EditHandler(MessageStore messageStore, PushService pushService) {
        this.messageStore = messageStore;
        this.pushService = pushService;
    }

    @Override
    public String cmd() {
        return Cmd.MSG_EDIT;
    }

    @Override
    public void handle(ChatContext ctx) {
        EditReq req = ctx.payload(EditReq.class);
        ChatMessage edited = messageStore.edit(req.messageId(), ctx.userId(), req.content())
                .orElseThrow(() -> new BizException(403, "只能编辑自己发的文本消息"));
        ctx.reply(Cmd.MSG_EDIT, edited);
        pushService.pushEdit(edited, ctx.session().deviceId());
    }
}
