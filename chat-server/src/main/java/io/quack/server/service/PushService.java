package io.quack.server.service;

import io.quack.common.model.ChatMessage;
import io.quack.common.model.ChatType;
import io.quack.common.protocol.Cmd;
import io.quack.common.protocol.Envelope;
import io.quack.server.store.OfflineCursorStore;
import io.quack.server.ws.SessionManager;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 消息投递：决定一条消息推给谁、在线直推、离线入箱。
 *
 * <p>把“投递策略”独立出来，是因为它会随规模变化：
 * 单机是内存路由；集群后要查 Redis 路由表，跨节点走 MQ；大群要改成拉模式。
 * 业务层（MessageService）不需要感知这些差异。
 */
@Service
public class PushService {

    private final SessionManager sessionManager;
    private final GroupService groupService;
    private final FriendService friendService;
    private final io.quack.server.store.OfflineCursorStore cursorStore;

    public PushService(SessionManager sessionManager, GroupService groupService,
                       FriendService friendService, io.quack.server.store.OfflineCursorStore cursorStore) {
        this.sessionManager = sessionManager;
        this.groupService = groupService;
        this.friendService = friendService;
        this.cursorStore = cursorStore;
    }

    /**
     * 把一条已提交消息推给相关设备。
     *
     * @param originDevice 发送动作所在的设备：它已经有了本地乐观消息，不再回推；
     *                     发送者的其它设备仍然要推（多端同步）
     */
    public void pushChatMessage(ChatMessage message, String originDevice) {
        // 发送设备自己：直接推进游标，避免重连后把自己发的再补一遍
        cursorStore.advance(message.from(), originDevice, message.conversationKey(), message.seq());

        if (message.chatType() == ChatType.PRIVATE) {
            deliver(message.to(), message, originDevice);
        } else {
            // 大群分页遍历成员，不一次性拷贝全群
            groupService.forEachMember(message.to(), member -> deliver(member, message, originDevice));
        }
    }

    private void deliver(String target, ChatMessage message, String originDevice) {
        Envelope envelope = Envelope.of(Cmd.MSG_PUSH, 0, message);
        for (var session : sessionManager.devicesOf(target)) {
            if (message.from().equals(target) && session.deviceId().equals(originDevice)) {
                continue;
            }
            session.send(envelope);
            cursorStore.advance(target, session.deviceId(), message.conversationKey(), message.seq());
        }
    }

    /** 撤回推送：会话内其它设备把这条消息替换成“已撤回” */
    public void pushRecall(ChatMessage message, String originDevice) {
        pushControl(message, originDevice, Cmd.MSG_RECALL_PUSH);
    }

    /** 编辑推送：会话内其它设备把这条消息替换成新内容 */
    public void pushEdit(ChatMessage message, String originDevice) {
        pushControl(message, originDevice, Cmd.MSG_EDIT_PUSH);
    }

    /** 控制类推送（撤回/编辑）：发给会话参与者，跳过发送动作所在设备 */
    private void pushControl(ChatMessage message, String originDevice, String cmd) {
        Envelope envelope = Envelope.of(cmd, 0, message);
        if (message.chatType() == ChatType.PRIVATE) {
            sendControl(message.to(), message, originDevice, envelope);
        } else {
            groupService.forEachMember(message.to(),
                    member -> sendControl(member, message, originDevice, envelope));
        }
    }

    private void sendControl(String target, ChatMessage message, String originDevice, Envelope envelope) {
        for (var session : sessionManager.devicesOf(target)) {
            if (message.from().equals(target) && session.deviceId().equals(originDevice)) {
                continue;
            }
            session.send(envelope);
        }
    }

    /**
     * 好友资料更新：只推给这个人的好友，不再广播全服。
     *
     * <p>原来遍历所有在线用户推 {@code friend.updated}，等于把昵称/头像变更
     * 泄露给陌生人；这里改成按好友关系定向推送。
     */
    public void broadcastUser(io.quack.common.model.User user, String excludeUserId) {
        if (user == null) {
            return;
        }
        Envelope envelope = Envelope.of(Cmd.FRIEND_UPDATED, 0, user);
        java.util.Set<String> targets = new java.util.LinkedHashSet<>();
        // 好友：会话列表 / 聊天头部的头像
        targets.addAll(friendService.friendIdsOf(user.id()));
        // 同群成员：群聊里非好友也能看到头像，必须一起推
        for (io.quack.common.model.ChatGroup group : groupService.groupsOf(user.id())) {
            targets.addAll(groupService.membersOf(group.id()));
        }
        // 本人：多端登录时，其它设备上的左下角头像 / 资料也要同步
        targets.add(user.id());
        for (String target : targets) {
            // 只跳过"不是本账号"的排除对象；自己的其它设备仍然要收
            if (target.equals(excludeUserId) && !target.equals(user.id())) {
                continue;
            }
            sessionManager.sendTo(target, envelope);
        }
    }

    /** 群资料/成员变了：把最新详情推给当前所有在线成员 */
    public void pushGroupUpdated(String groupId) {
        var detail = groupService.detail(groupId).orElse(null);
        if (detail == null) {
            return;
        }
        Envelope envelope = Envelope.of(Cmd.GROUP_UPDATED_PUSH, 0, detail);
        for (String member : groupService.membersOf(groupId)) {
            sessionManager.sendTo(member, envelope);
        }
    }

    /** 某人被移出群/群解散：只通知当事人，客户端据此移除会话 */
    public void pushGroupRemoved(String groupId, String userId, String reason) {
        sessionManager.sendTo(userId,
                Envelope.of(Cmd.GROUP_REMOVED_PUSH, 0,
                        new io.quack.common.dto.GroupRemoved(groupId, reason)));
    }

    public void forwardTransient(String userId, Envelope envelope) {
        if (sessionManager.isOnline(userId)) {
            sessionManager.sendTo(userId, envelope);
        }
    }
}
