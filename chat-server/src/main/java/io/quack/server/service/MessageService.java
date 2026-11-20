package io.quack.server.service;

import io.quack.common.dto.HistoryReq;
import io.quack.common.dto.SendMessageAck;
import io.quack.common.dto.SendMessageReq;
import io.quack.common.model.ChatMessage;
import io.quack.common.model.ChatType;
import io.quack.common.model.MsgType;
import io.quack.server.store.MessageStore;
import io.quack.server.support.BizException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 消息业务：校验 -&gt; 落库 -&gt; 投递 -&gt; 回执。
 *
 * <p>这里的顺序很关键：先落库再投递。如果先投递再落库，对方可能收到一条
 * “查不到”的消息；而先落库再投递，即使投递失败也只是延迟（离线补发兜底）。
 */
@Service
public class MessageService {

    private static final int MAX_CONTENT_LENGTH = 2000;

    private final MessageStore messageStore;
    private final PushService pushService;
    private final GroupService groupService;
    private final UserService userService;
    private final Metrics metrics;
    private final UnreadService unreadService;

    public MessageService(MessageStore messageStore, PushService pushService,
                          GroupService groupService, UserService userService, Metrics metrics,
                          UnreadService unreadService) {
        this.messageStore = messageStore;
        this.pushService = pushService;
        this.groupService = groupService;
        this.userService = userService;
        this.metrics = metrics;
        this.unreadService = unreadService;
    }

    /**
     * @param from     发送者 id
     * @param fromName 发送者昵称快照（必须在投递前确定，否则对方收到的消息里名字是空的）
     */
    public SendMessageAck send(String from, String fromName, SendMessageReq req, String originDevice) {
        if (req.to() == null || req.to().isBlank()) {
            throw BizException.badRequest("收件人不能为空");
        }
        String content = req.content() == null ? "" : req.content().trim();
        if (content.isEmpty()) {
            throw BizException.badRequest("消息内容不能为空");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw BizException.badRequest("消息内容不能超过 " + MAX_CONTENT_LENGTH + " 字");
        }

        ChatType chatType = req.chatType() == null ? ChatType.PRIVATE : req.chatType();
        if (chatType == ChatType.GROUP && !groupService.contains(req.to(), from)) {
            throw BizException.forbidden("你不在该群，无法发送消息");
        }
        if (chatType == ChatType.PRIVATE) {
            if (req.to().equals(from)) {
                throw BizException.badRequest("不能给自己发消息");
            }
            if (userService.byId(req.to()).isEmpty()) {
                throw new BizException(404, "收件人不存在");
            }
        }
        MsgType msgType = req.msgType() == null ? MsgType.TEXT : req.msgType();

        // 幂等：同一条消息重发（比如断线重连后客户端补发）只落库/投递一次，
        // 直接返回原来的 ACK。之前注释写着幂等，实际没有任何去重。
        ChatMessage existing = messageStore.byClientKey(from, req.clientMsgId()).orElse(null);
        if (existing != null) {
            return new SendMessageAck(existing.id(), existing.clientMsgId(), existing.ts(), existing.seq());
        }

        String fromAvatar = userService.byId(from)
                .map(io.quack.common.model.User::avatar).orElse(null);
        String senderName = fromName;
        if (chatType == ChatType.GROUP) {
            String alias = groupService.aliasOf(req.to(), from);
            if (alias != null && !alias.isBlank()) {
                senderName = alias;
            }
        }
        ChatMessage message = new ChatMessage(
                UUID.randomUUID().toString(),
                req.clientMsgId(),
                from,
                senderName,
                req.to(),
                chatType,
                msgType,
                content,
                System.currentTimeMillis(),
                fromAvatar);

        // 落库时分配会话内 seq，之后所有排序/同步/已读都以它为准
        ChatMessage saved = messageStore.save(message);
        pushService.pushChatMessage(saved, originDevice);
        if (saved.chatType() == io.quack.common.model.ChatType.PRIVATE) {
            unreadService.incrementForMessage(saved);
        } else {
            groupService.forEachMember(saved.to(), member -> unreadService.incrementForGroup(member, saved));
        }
        metrics.onMessageAccepted();
        return new SendMessageAck(saved.id(), saved.clientMsgId(), saved.ts(), saved.seq());
    }

    /** 增量同步：给某个用户补某个会话在 sinceSeq 之后的消息 */
    public io.quack.common.dto.SyncResp sync(String userId, io.quack.common.dto.SyncReq req) {
        io.quack.common.model.ChatType chatType = req.chatType() == null
                ? io.quack.common.model.ChatType.PRIVATE : req.chatType();
        String peerId = req.peerId();
        if (peerId == null || peerId.isBlank()) {
            throw BizException.badRequest("会话 id 不能为空");
        }
        if (chatType == io.quack.common.model.ChatType.GROUP
                && !groupService.contains(peerId, userId)) {
            throw BizException.forbidden("你不在该群，无法同步消息");
        }
        int limit = req.limit() <= 0 ? 100 : Math.min(req.limit(), 500);
        java.util.List<ChatMessage> messages = messageStore.sync(chatType, userId, peerId,
                req.sinceSeq(), limit);
        long latest = messageStore.latestSeq(chatType, userId, peerId);
        boolean hasMore;
        if (messages.isEmpty()) {
            hasMore = false;
        } else if (req.sinceSeq() <= 0) {
            // 返回的是“最近 N 条”，前面还有更早的就说明还有缺口
            hasMore = messages.get(0).seq() > 1;
        } else {
            hasMore = messages.get(messages.size() - 1).seq() < latest;
        }
        return new io.quack.common.dto.SyncResp(messages, latest, hasMore);
    }

    public List<ChatMessage> history(String userId, HistoryReq req) {
        ChatType chatType = req.chatType() == null ? ChatType.PRIVATE : req.chatType();
        String peerId = req.peerId();
        if (peerId == null || peerId.isBlank()) {
            throw BizException.badRequest("会话 id 不能为空");
        }
        if (chatType == ChatType.GROUP && !groupService.contains(peerId, userId)) {
            throw BizException.forbidden("你不在该群，无法查看历史消息");
        }
        return messageStore.history(userId, chatType, peerId, req.beforeTs(), req.limit() <= 0 ? 50 : req.limit());
    }
}
