package io.quack.server.dispatch.handler;

import io.quack.common.dto.RecvReq;
import io.quack.common.model.ChatType;
import io.quack.common.protocol.Cmd;
import io.quack.server.dispatch.ChatContext;
import io.quack.server.dispatch.CommandHandler;
import io.quack.server.store.OfflineCursorStore;
import org.springframework.stereotype.Component;

/**
 * 接收确认：客户端处理完推送后推进这台设备的离线游标。
 *
 * <p>没有回执（单向上报）；离线补发是否重复由客户端按 id/seq 去重兜底。
 */
@Component
public class RecvHandler implements CommandHandler {

    private final OfflineCursorStore cursorStore;

    public RecvHandler(OfflineCursorStore cursorStore) {
        this.cursorStore = cursorStore;
    }

    @Override
    public String cmd() {
        return Cmd.MSG_RECV;
    }

    @Override
    public void handle(ChatContext ctx) {
        RecvReq req = ctx.payload(RecvReq.class);
        ChatType chatType = "GROUP".equalsIgnoreCase(req.chatType())
                ? ChatType.GROUP : ChatType.PRIVATE;
        String key = OfflineCursorStore.conversationKey(chatType, ctx.userId(), req.peerId());
        cursorStore.advance(ctx.userId(), ctx.session().deviceId(), key, req.seq());
    }
}
