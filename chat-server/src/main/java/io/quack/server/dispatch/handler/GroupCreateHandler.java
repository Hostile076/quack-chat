package io.quack.server.dispatch.handler;

import io.quack.common.dto.GroupCreateReq;
import io.quack.common.dto.GroupDetailResp;
import io.quack.common.model.ChatGroup;
import io.quack.common.model.GroupDetail;
import io.quack.common.protocol.Cmd;
import io.quack.server.dispatch.ChatContext;
import io.quack.server.dispatch.CommandHandler;
import io.quack.server.service.FriendService;
import io.quack.server.service.GroupService;
import io.quack.server.service.PushService;
import io.quack.server.service.UserService;
import io.quack.server.support.BizException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 创建群聊：我是群主，把选中的好友拉进来。
 *
 * <p>只允许拉好友（防止拿任意 QC 号建群骚扰）；建好后给所有成员推
 * {@code group.updated.push}，客户端收到后会自动建出这个群的会话。
 */
@Component
public class GroupCreateHandler implements CommandHandler {

    private static final int MAX_NAME_LENGTH = 20;
    private static final int MAX_MEMBERS = 200;

    private final GroupService groupService;
    private final FriendService friendService;
    private final PushService pushService;
    private final UserService userService;

    public GroupCreateHandler(GroupService groupService, FriendService friendService,
                              PushService pushService, UserService userService) {
        this.groupService = groupService;
        this.friendService = friendService;
        this.pushService = pushService;
        this.userService = userService;
    }

    @Override
    public String cmd() {
        return Cmd.GROUP_CREATE;
    }

    @Override
    public void handle(ChatContext ctx) {
        GroupCreateReq req = ctx.payload(GroupCreateReq.class);
        String name = req.name() == null ? "" : req.name().trim();
        if (name.isEmpty()) {
            throw BizException.badRequest("请填写群名称");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw BizException.badRequest("群名称不能超过 " + MAX_NAME_LENGTH + " 个字");
        }

        Set<String> members = new LinkedHashSet<>();
        if (req.memberIds() != null) {
            for (String id : req.memberIds()) {
                if (id == null || id.isBlank() || id.equals(ctx.userId())) {
                    continue;
                }
                if (userService.byId(id).isEmpty()) {
                    throw BizException.badRequest("好友不存在：" + id);
                }
                if (!friendService.areFriends(ctx.userId(), id)) {
                    throw BizException.badRequest("只能把好友拉进群");
                }
                members.add(id);
            }
        }
        if (members.isEmpty()) {
            throw BizException.badRequest("至少选择一个好友");
        }
        if (members.size() + 1 > MAX_MEMBERS) {
            throw BizException.badRequest("群成员不能超过 " + MAX_MEMBERS + " 人");
        }

        ChatGroup group = groupService.create(name, ctx.userId(), new ArrayList<>(members));
        GroupDetail detail = groupService.detail(group.id())
                .orElseThrow(() -> new BizException(500, "群创建失败"));
        ctx.reply(Cmd.GROUP_CREATE_RESP, new GroupDetailResp(detail));
        // 所有成员（含自己）都推一次：其他人的客户端靠这条推送建出会话
        pushService.pushGroupUpdated(group.id());
    }
}
