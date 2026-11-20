package io.quack.server.dispatch.handler;

import io.quack.common.dto.HistoryReq;
import io.quack.common.dto.HistoryResp;
import io.quack.common.model.ChatMessage;
import io.quack.common.model.ChatType;
import io.quack.common.protocol.Cmd;
import io.quack.server.dispatch.ChatContext;
import io.quack.server.dispatch.CommandHandler;
import io.quack.server.service.MessageService;
import org.springframework.stereotype.Component;

import java.util.List;

/** 历史消息查询（上滑加载更多时把 beforeTs 设为当前最早一条的时间即可） */
@Component
public class HistoryHandler implements CommandHandler {

    private final MessageService messageService;

    public HistoryHandler(MessageService messageService) {
        this.messageService = messageService;
    }

    @Override
    public String cmd() {
        return Cmd.HISTORY_REQ;
    }

    @Override
    public void handle(ChatContext ctx) {
        HistoryReq req = ctx.payload(HistoryReq.class);
        List<ChatMessage> messages = messageService.history(ctx.userId(), req);
        ChatType chatType = req.chatType() == null ? ChatType.PRIVATE : req.chatType();
        int limit = req.limit() <= 0 ? 50 : req.limit();
        ctx.reply(Cmd.HISTORY_RESP,
                new HistoryResp(req.peerId(), chatType, messages, messages.size() >= limit));
    }
}
