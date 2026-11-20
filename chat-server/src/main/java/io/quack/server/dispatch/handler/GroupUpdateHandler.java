package io.quack.server.dispatch.handler;

import io.quack.common.dto.GroupDetailResp;
import io.quack.common.dto.GroupUpdateReq;
import io.quack.common.model.GroupDetail;
import io.quack.common.protocol.Cmd;
import io.quack.server.dispatch.ChatContext;
import io.quack.server.dispatch.CommandHandler;
import io.quack.server.service.GroupService;
import io.quack.server.support.BizException;
import org.springframework.stereotype.Component;

/** 修改群名 / 群公告：只有群主能改 */
@Component
public class GroupUpdateHandler implements CommandHandler {

    private final GroupService groupService;
    private final io.quack.server.service.PushService pushService;

    public GroupUpdateHandler(GroupService groupService,
                              io.quack.server.service.PushService pushService) {
        this.groupService = groupService;
        this.pushService = pushService;
    }

    @Override
    public String cmd() {
        return Cmd.GROUP_UPDATE;
    }

    @Override
    public void handle(ChatContext ctx) {
        GroupUpdateReq req = ctx.payload(GroupUpdateReq.class);
        String groupId = req.groupId() == null ? "" : req.groupId().trim();
        if (!groupService.contains(groupId, ctx.userId())) {
            throw BizException.forbidden("你不在该群");
        }
        if (!groupService.isManager(groupId, ctx.userId())) {
            throw BizException.forbidden("只有群主或管理员可以修改群资料");
        }
        if (req.name() != null && req.name().trim().length() > 30) {
            throw BizException.badRequest("群名称不能超过 30 个字");
        }
        if (req.announcement() != null && req.announcement().trim().length() > 500) {
            throw BizException.badRequest("群公告不能超过 500 个字");
        }
        GroupDetail detail = groupService.update(groupId, req.name(), req.announcement(), req.avatar())
                .orElseThrow(() -> new BizException(404, "群不存在"));
        ctx.reply(Cmd.GROUP_UPDATE_RESP, new GroupDetailResp(detail));
        // 群资料（名称/公告/头像）变了，推给其他成员
        for (String member : groupService.membersOf(groupId)) {
            if (!member.equals(ctx.userId())) {
                pushService.forwardTransient(member,
                        io.quack.common.protocol.Envelope.of(Cmd.GROUP_UPDATED_PUSH, 0, detail));
            }
        }
    }
}
