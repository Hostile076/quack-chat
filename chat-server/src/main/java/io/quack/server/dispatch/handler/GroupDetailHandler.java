package io.quack.server.dispatch.handler;

import io.quack.common.dto.GroupDetailReq;
import io.quack.common.dto.GroupDetailResp;
import io.quack.common.model.GroupDetail;
import io.quack.common.protocol.Cmd;
import io.quack.server.dispatch.ChatContext;
import io.quack.server.dispatch.CommandHandler;
import io.quack.server.service.GroupService;
import io.quack.server.support.BizException;
import org.springframework.stereotype.Component;

/** 群主页数据：群名、群号、公告、成员列表 */
@Component
public class GroupDetailHandler implements CommandHandler {

    private final GroupService groupService;

    public GroupDetailHandler(GroupService groupService) {
        this.groupService = groupService;
    }

    @Override
    public String cmd() {
        return Cmd.GROUP_DETAIL;
    }

    @Override
    public void handle(ChatContext ctx) {
        GroupDetailReq req = ctx.payload(GroupDetailReq.class);
        String groupId = req.groupId() == null ? "" : req.groupId().trim();
        if (groupService.byId(groupId).isEmpty()) {
            throw new BizException(404, "群不存在");
        }
        if (!groupService.contains(groupId, ctx.userId())) {
            throw BizException.forbidden("你不在该群，无法查看群资料");
        }
        GroupDetail detail = groupService.detail(groupId)
                .orElseThrow(() -> new BizException(404, "群不存在"));
        ctx.reply(Cmd.GROUP_DETAIL_RESP, new GroupDetailResp(detail));
    }
}
