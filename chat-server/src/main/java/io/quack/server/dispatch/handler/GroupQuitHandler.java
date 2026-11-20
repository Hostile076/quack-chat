package io.quack.server.dispatch.handler;

import io.quack.common.dto.GroupQuitReq;
import io.quack.common.protocol.Cmd;
import io.quack.server.dispatch.ChatContext;
import io.quack.server.dispatch.CommandHandler;
import io.quack.server.service.GroupService;
import io.quack.server.support.BizException;
import org.springframework.stereotype.Component;

/** 退出群聊：群主退出会自动转让群主 */
@Component
public class GroupQuitHandler implements CommandHandler {

    private final GroupService groupService;
    private final io.quack.server.service.PushService pushService;

    public GroupQuitHandler(GroupService groupService, io.quack.server.service.PushService pushService) {
        this.groupService = groupService;
        this.pushService = pushService;
    }

    @Override
    public String cmd() {
        return Cmd.GROUP_QUIT;
    }

    @Override
    public void handle(ChatContext ctx) {
        GroupQuitReq req = ctx.payload(GroupQuitReq.class);
        String groupId = req.groupId() == null ? "" : req.groupId().trim();
        if (!groupService.contains(groupId, ctx.userId())) {
            throw BizException.badRequest("你不在该群");
        }
        groupService.quit(groupId, ctx.userId());
        ctx.reply(Cmd.GROUP_QUIT_RESP, null);
        // 退群本人（包括其它设备）也要立刻把会话移除，避免留下“僵尸群”
        pushService.pushGroupRemoved(groupId, ctx.userId(), "quit");
        // 群还在的话，剩下成员的成员列表/群主标记要跟着变
        pushService.pushGroupUpdated(groupId);
    }
}
