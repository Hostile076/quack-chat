package io.quack.server.dispatch.handler;

import io.quack.common.dto.EssenceListResp;
import io.quack.common.dto.GroupAdminReq;
import io.quack.common.dto.GroupAnnouncementReq;
import io.quack.common.dto.GroupAliasReq;
import io.quack.common.dto.GroupDetailResp;
import io.quack.common.dto.GroupDissolveReq;
import io.quack.common.dto.GroupInviteReq;
import io.quack.common.dto.GroupMemberOpReq;
import io.quack.common.model.ChatMessage;
import io.quack.common.model.GroupDetail;
import io.quack.common.protocol.Cmd;
import io.quack.server.dispatch.ChatContext;
import io.quack.server.dispatch.MultiCommandHandler;
import io.quack.server.service.EssenceService;
import io.quack.server.service.GroupService;
import io.quack.server.store.MessageStore;
import io.quack.server.support.BizException;
import org.springframework.stereotype.Component;

import java.util.List;

/** 群管理：邀请、踢人、转让群主、精华消息 */
@Component
public class GroupAdminHandler implements MultiCommandHandler {

    private final GroupService groupService;
    private final EssenceService essenceService;
    private final MessageStore messageStore;
    private final io.quack.server.service.PushService pushService;
    private final io.quack.server.service.UserService userService;
    private final io.quack.server.service.GroupJoinService groupJoinService;

    public GroupAdminHandler(GroupService groupService, EssenceService essenceService,
                             MessageStore messageStore, io.quack.server.service.PushService pushService,
                             io.quack.server.service.UserService userService,
                             io.quack.server.service.GroupJoinService groupJoinService) {
        this.groupService = groupService;
        this.essenceService = essenceService;
        this.messageStore = messageStore;
        this.pushService = pushService;
        this.userService = userService;
        this.groupJoinService = groupJoinService;
    }

    @Override
    public List<String> cmds() {
        return List.of(Cmd.GROUP_INVITE, Cmd.GROUP_KICK, Cmd.GROUP_TRANSFER,
                Cmd.GROUP_ESSENCE_ADD, Cmd.GROUP_ESSENCE_REMOVE, Cmd.GROUP_ESSENCE_LIST,
                Cmd.GROUP_ALIAS, Cmd.GROUP_ANNOUNCEMENT_ADD,
                Cmd.GROUP_ADMIN_ADD, Cmd.GROUP_ADMIN_REMOVE, Cmd.GROUP_DISSOLVE);
    }

    @Override
    public void handle(ChatContext ctx) {
        switch (ctx.cmd()) {
            case Cmd.GROUP_INVITE -> invite(ctx);
            case Cmd.GROUP_KICK -> kick(ctx);
            case Cmd.GROUP_TRANSFER -> transfer(ctx);
            case Cmd.GROUP_ESSENCE_ADD -> essenceAdd(ctx);
            case Cmd.GROUP_ESSENCE_REMOVE -> essenceRemove(ctx);
            case Cmd.GROUP_ESSENCE_LIST -> essenceList(ctx);
            case Cmd.GROUP_ALIAS -> alias(ctx);
            case Cmd.GROUP_ANNOUNCEMENT_ADD -> announcement(ctx);
            case Cmd.GROUP_ADMIN_ADD -> adminAdd(ctx);
            case Cmd.GROUP_ADMIN_REMOVE -> adminRemove(ctx);
            case Cmd.GROUP_DISSOLVE -> dissolve(ctx);
            default -> ctx.fail(404, "未知命令: " + ctx.cmd());
        }
    }

    private void invite(ChatContext ctx) {
        GroupInviteReq req = ctx.payload(GroupInviteReq.class);
        requireManager(req.groupId(), ctx.userId());
        if (req.userIds() == null || req.userIds().isEmpty()) {
            throw BizException.badRequest("请选择要邀请的好友");
        }
        // 过滤 null/空串并校验用户存在：否则要么 NPE 500，要么拉进一个不存在的“幽灵成员”
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (String id : req.userIds()) {
            if (id == null || id.isBlank()) {
                continue;
            }
            String trimmed = id.trim();
            if (userService.byId(trimmed).isEmpty()) {
                throw new BizException(404, "用户不存在: " + trimmed);
            }
            if (!ids.contains(trimmed)) {
                ids.add(trimmed);
            }
        }
        if (ids.isEmpty()) {
            throw BizException.badRequest("请选择要邀请的好友");
        }
        groupService.invite(req.groupId(), ids);
        ctx.reply(Cmd.GROUP_UPDATE_RESP, new GroupDetailResp(detail(req.groupId())));
        // 新成员和其它成员的客户端都要刷新成员列表（否则被邀请的人要重新登录才看得到群）
        pushService.pushGroupUpdated(req.groupId());
    }

    private void kick(ChatContext ctx) {
        GroupMemberOpReq req = ctx.payload(GroupMemberOpReq.class);
        requireManager(req.groupId(), ctx.userId());
        if (req.userId() == null || req.userId().isBlank()) {
            throw BizException.badRequest("请选择要移出的成员");
        }
        if (req.userId().equals(ctx.userId())) {
            throw BizException.badRequest("不能移出自己，请使用退出群聊");
        }
        if (groupService.isOwner(req.groupId(), req.userId())) {
            throw BizException.badRequest("不能移出群主");
        }
        groupService.kick(req.groupId(), req.userId());
        ctx.reply(Cmd.GROUP_UPDATE_RESP, new GroupDetailResp(detail(req.groupId())));
        // 被踢的人要立刻丢掉这个群，剩下的人要看到成员数变化
        pushService.pushGroupRemoved(req.groupId(), req.userId(), "kicked");
        pushService.pushGroupUpdated(req.groupId());
    }

    private void transfer(ChatContext ctx) {
        GroupMemberOpReq req = ctx.payload(GroupMemberOpReq.class);
        requireOwner(req.groupId(), ctx.userId());
        if (req.userId() == null || !groupService.contains(req.groupId(), req.userId())) {
            throw BizException.badRequest("对方不在这个群里");
        }
        groupService.transfer(req.groupId(), req.userId());
        ctx.reply(Cmd.GROUP_UPDATE_RESP, new GroupDetailResp(detail(req.groupId())));
        // 群主变了，所有人的群主页「群主标记」都要刷新
        pushService.pushGroupUpdated(req.groupId());
    }

    private void alias(ChatContext ctx) {
        GroupAliasReq req = ctx.payload(GroupAliasReq.class);
        if (req.groupId() == null || !groupService.contains(req.groupId(), ctx.userId())) {
            throw BizException.forbidden("你不在该群");
        }
        if (req.alias() != null && req.alias().trim().length() > 20) {
            throw BizException.badRequest("群昵称不能超过 20 个字");
        }
        GroupDetail updated = groupService.setAlias(req.groupId(), ctx.userId(), req.alias())
                .orElseThrow(() -> new BizException(404, "群不存在"));
        ctx.reply(Cmd.GROUP_UPDATE_RESP, new GroupDetailResp(updated));
        pushService.pushGroupUpdated(req.groupId());
    }

    private void announcement(ChatContext ctx) {
        GroupAnnouncementReq req = ctx.payload(GroupAnnouncementReq.class);
        requireManager(req.groupId(), ctx.userId());
        if (req.text() == null || req.text().trim().isEmpty()) {
            throw BizException.badRequest("公告内容不能为空");
        }
        if (req.text().trim().length() > 500) {
            throw BizException.badRequest("群公告不能超过 500 个字");
        }
        GroupDetail updated = groupService.addAnnouncement(req.groupId(), ctx.userId(),
                        userService.nicknameOf(ctx.userId()), req.text())
                .orElseThrow(() -> new BizException(404, "群不存在"));
        ctx.reply(Cmd.GROUP_UPDATE_RESP, new GroupDetailResp(updated));
        pushService.pushGroupUpdated(req.groupId());
    }

    private void essenceAdd(ChatContext ctx) {
        GroupMemberOpReq req = ctx.payload(GroupMemberOpReq.class);
        requireManager(req.groupId(), ctx.userId());
        ChatMessage message = messageStore.byId(req.messageId())
                .orElseThrow(() -> new BizException(404, "消息不存在"));
        if (!essenceService.belongsTo(message, req.groupId())) {
            throw BizException.badRequest("这条消息不属于该群");
        }
        essenceService.add(req.groupId(), req.messageId());
        ctx.reply(Cmd.GROUP_ESSENCE_LIST_RESP, new EssenceListResp(essenceService.list(req.groupId())));
    }

    private void essenceRemove(ChatContext ctx) {
        GroupMemberOpReq req = ctx.payload(GroupMemberOpReq.class);
        requireManager(req.groupId(), ctx.userId());
        essenceService.remove(req.groupId(), req.messageId());
        ctx.reply(Cmd.GROUP_ESSENCE_LIST_RESP, new EssenceListResp(essenceService.list(req.groupId())));
    }

    private void essenceList(ChatContext ctx) {
        GroupMemberOpReq req = ctx.payload(GroupMemberOpReq.class);
        if (!groupService.contains(req.groupId(), ctx.userId())) {
            throw BizException.forbidden("你不在该群");
        }
        ctx.reply(Cmd.GROUP_ESSENCE_LIST_RESP, new EssenceListResp(essenceService.list(req.groupId())));
    }

    private void adminAdd(ChatContext ctx) {
        GroupAdminReq req = ctx.payload(GroupAdminReq.class);
        requireOwner(req.groupId(), ctx.userId());
        if (req.userId() == null || !groupService.contains(req.groupId(), req.userId())) {
            throw BizException.badRequest("对方不在这个群里");
        }
        if (groupService.isOwner(req.groupId(), req.userId())) {
            throw BizException.badRequest("群主不需要设为管理员");
        }
        groupService.addAdmin(req.groupId(), req.userId());
        ctx.reply(Cmd.GROUP_UPDATE_RESP, new GroupDetailResp(detail(req.groupId())));
        pushService.pushGroupUpdated(req.groupId());
    }

    private void adminRemove(ChatContext ctx) {
        GroupAdminReq req = ctx.payload(GroupAdminReq.class);
        requireOwner(req.groupId(), ctx.userId());
        if (req.userId() == null || !groupService.contains(req.groupId(), req.userId())) {
            throw BizException.badRequest("对方不在这个群里");
        }
        groupService.removeAdmin(req.groupId(), req.userId());
        ctx.reply(Cmd.GROUP_UPDATE_RESP, new GroupDetailResp(detail(req.groupId())));
        pushService.pushGroupUpdated(req.groupId());
    }

    private void dissolve(ChatContext ctx) {
        GroupDissolveReq req = ctx.payload(GroupDissolveReq.class);
        requireOwner(req.groupId(), ctx.userId());
        java.util.Set<String> members = groupService.dissolve(req.groupId());
        groupJoinService.removeGroup(req.groupId());
        ctx.reply(Cmd.GROUP_UPDATE_RESP, new GroupDetailResp(null));
        for (String member : members) {
            pushService.pushGroupRemoved(req.groupId(), member, "dismissed");
        }
    }

    private void requireOwner(String groupId, String userId) {
        if (groupId == null || !groupService.contains(groupId, userId)) {
            throw BizException.forbidden("你不在该群");
        }
        if (!groupService.isOwner(groupId, userId)) {
            throw BizException.forbidden("只有群主可以操作");
        }
    }

    private void requireManager(String groupId, String userId) {
        if (groupId == null || !groupService.contains(groupId, userId)) {
            throw BizException.forbidden("你不在该群");
        }
        if (!groupService.isManager(groupId, userId)) {
            throw BizException.forbidden("只有群主或管理员可以操作");
        }
    }

    private GroupDetail detail(String groupId) {
        return groupService.detail(groupId).orElseThrow(() -> new BizException(404, "群不存在"));
    }
}
