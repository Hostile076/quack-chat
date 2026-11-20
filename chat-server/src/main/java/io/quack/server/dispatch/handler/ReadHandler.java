package io.quack.server.dispatch.handler;

import io.quack.common.dto.ReadReq;
import io.quack.common.model.ChatType;
import io.quack.common.protocol.Cmd;
import io.quack.server.dispatch.ChatContext;
import io.quack.server.dispatch.CommandHandler;
import io.quack.server.service.MessageService;
import io.quack.server.service.ReadService;
import io.quack.server.store.MessageStore;
import org.springframework.stereotype.Component;

/**
 * 已读上报。
 *
 * <p>优先用 seq；旧客户端只传 ts 时，服务端按会话把它换算成“该时间点对应的 seq”，
 * 保证位点仍然单调、可比较。
 */
@Component
public class ReadHandler implements CommandHandler {

    private final ReadService readService;
    private final MessageStore messageStore;
    private final io.quack.server.service.UnreadService unreadService;
    private final io.quack.server.ws.SessionManager sessionManager;

    public ReadHandler(ReadService readService, MessageStore messageStore,
                       io.quack.server.service.UnreadService unreadService,
                       io.quack.server.ws.SessionManager sessionManager) {
        this.readService = readService;
        this.messageStore = messageStore;
        this.unreadService = unreadService;
        this.sessionManager = sessionManager;
    }

    @Override
    public String cmd() {
        return Cmd.MSG_READ;
    }

    @Override
    public void handle(ChatContext ctx) {
        ReadReq req = ctx.payload(ReadReq.class);
        ChatType chatType = "GROUP".equalsIgnoreCase(req.chatType())
                ? ChatType.GROUP : ChatType.PRIVATE;
        long seq = req.seq();
        if (seq <= 0 && req.ts() > 0) {
            seq = messageStore.seqAtOrBefore(chatType, ctx.userId(), req.peerId(), req.ts());
        }
        readService.markRead(ctx.userId(), req.peerId(), chatType.name(), req.ts(), seq);

        // 服务端未读数：把该会话里 seq 之后还剩多少条算出来，并同步给同账号所有设备
        String key = io.quack.server.store.OfflineCursorStore.conversationKey(
                chatType, ctx.userId(), req.peerId());
        int remaining = messageStore.sync(chatType, ctx.userId(), req.peerId(), seq, 500).size();
        unreadService.setUnread(ctx.userId(), key, remaining);
        io.quack.common.protocol.Envelope push = io.quack.common.protocol.Envelope.of(
                Cmd.UNREAD_PUSH, 0, new io.quack.common.dto.UnreadPush(key, remaining));
        for (var session : sessionManager.devicesOf(ctx.userId())) {
            session.send(push);
        }
        // 每个请求都要有回包，否则客户端按 seq 配对会一直挂起
        ctx.reply(Cmd.MSG_READ_RESP, new io.quack.common.dto.UnreadPush(key, remaining));
    }
}
