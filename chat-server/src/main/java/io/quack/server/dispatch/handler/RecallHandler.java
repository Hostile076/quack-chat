package io.quack.server.dispatch.handler;

import io.quack.common.dto.RecallReq;
import io.quack.common.model.ChatMessage;
import io.quack.common.protocol.Cmd;
import io.quack.server.dispatch.ChatContext;
import io.quack.server.dispatch.CommandHandler;
import io.quack.server.service.PushService;
import io.quack.server.store.MessageStore;
import io.quack.server.support.BizException;
import org.springframework.stereotype.Component;

/** 撤回消息：只能撤自己发的，且 2 分钟内 */
@Component
public class RecallHandler implements CommandHandler {

    private static final long WINDOW_MS = 2 * 60 * 1000;

    private final MessageStore messageStore;
    private final PushService pushService;
    private final io.quack.server.service.UnreadService unreadService;
    private final io.quack.server.service.GroupService groupService;

    public RecallHandler(MessageStore messageStore, PushService pushService,
                         io.quack.server.service.UnreadService unreadService,
                         io.quack.server.service.GroupService groupService) {
        this.messageStore = messageStore;
        this.pushService = pushService;
        this.unreadService = unreadService;
        this.groupService = groupService;
    }

    @Override
    public String cmd() {
        return Cmd.MSG_RECALL;
    }

    @Override
    public void handle(ChatContext ctx) {
        RecallReq req = ctx.payload(RecallReq.class);
        ChatMessage recalled = messageStore.recall(req.messageId(), ctx.userId(), WINDOW_MS)
                .orElseThrow(() -> new BizException(403, "消息不存在、不是你的消息，或已超过可撤回时间"));
        ctx.reply(Cmd.MSG_RECALL, recalled);
        // 会话内其它设备/成员同步替换；发送方本设备已经拿到 reply
        pushService.pushRecall(recalled, ctx.session().deviceId());

        // 撤回的如果是别人还没读的消息，未读数要减一
        String key = recalled.conversationKey();
        if (recalled.chatType() == io.quack.common.model.ChatType.PRIVATE) {
            unreadService.decrement(recalled.to(), key);
        } else {
            groupService.forEachMember(recalled.to(), member -> {
                if (!member.equals(recalled.from())) {
                    unreadService.decrement(member, key);
                }
            });
        }
    }
}
