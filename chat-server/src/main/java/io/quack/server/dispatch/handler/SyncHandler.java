package io.quack.server.dispatch.handler;

import io.quack.common.dto.SyncReq;
import io.quack.common.dto.SyncResp;
import io.quack.common.protocol.Cmd;
import io.quack.server.dispatch.ChatContext;
import io.quack.server.dispatch.CommandHandler;
import io.quack.server.service.MessageService;
import io.quack.server.store.OfflineCursorStore;
import org.springframework.stereotype.Component;

/**
 * 增量同步。
 *
 * <p>断线重连、换设备、重装客户端都走这一条：带上本地已有的最大 seq，
 * 只补缺口。比“按时间戳翻页”可靠，也不会因为时钟回拨漏消息。
 */
@Component
public class SyncHandler implements CommandHandler {

    private final MessageService messageService;
    private final OfflineCursorStore cursorStore;
    private final io.quack.server.service.Metrics metrics;

    public SyncHandler(MessageService messageService, OfflineCursorStore cursorStore,
                       io.quack.server.service.Metrics metrics) {
        this.messageService = messageService;
        this.cursorStore = cursorStore;
        this.metrics = metrics;
    }

    @Override
    public String cmd() {
        return Cmd.SYNC_REQ;
    }

    @Override
    public void handle(ChatContext ctx) {
        SyncReq req = ctx.payload(SyncReq.class);
        SyncResp resp = messageService.sync(ctx.userId(), req);
        ctx.reply(Cmd.SYNC_RESP, resp);
        if (resp.messages() != null && !resp.messages().isEmpty()) {
            metrics.onMessagesDelivered(resp.messages().size());
            // 客户端已经拿到这批消息，推进离线游标，避免下次登录重复补发
            String key = OfflineCursorStore.conversationKey(
                    req.chatType() == null ? io.quack.common.model.ChatType.PRIVATE : req.chatType(),
                    ctx.userId(), req.peerId());
            cursorStore.advance(ctx.userId(), ctx.session().deviceId(), key,
                    resp.messages().get(resp.messages().size() - 1).seq());
        }
    }
}
