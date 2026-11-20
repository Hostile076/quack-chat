package io.quack.server.dispatch.handler;

import io.quack.common.dto.GroupDetailResp;
import io.quack.common.dto.GroupJoinHandleReq;
import io.quack.common.dto.GroupJoinReq;
import io.quack.common.dto.GroupJoinRequestsResp;
import io.quack.common.dto.GroupSearchReq;
import io.quack.common.dto.GroupSearchResp;
import io.quack.common.model.GroupDetail;
import io.quack.common.model.User;
import io.quack.common.protocol.Cmd;
import io.quack.server.dispatch.ChatContext;
import io.quack.server.dispatch.MultiCommandHandler;
import io.quack.server.service.GroupJoinService;
import io.quack.server.service.GroupService;
import io.quack.server.service.UserService;
import io.quack.server.support.BizException;
import org.springframework.stereotype.Component;

import java.util.List;

/** 搜索群 / 申请入群 / 入群审批 */
@Component
public class GroupDiscoveryHandler implements MultiCommandHandler {

    private final GroupService groupService;
    private final GroupJoinService groupJoinService;
    private final UserService userService;
    private final io.quack.server.service.PushService pushService;

    public GroupDiscoveryHandler(GroupService groupService, GroupJoinService groupJoinService,
                                 UserService userService,
                                 io.quack.server.service.PushService pushService) {
        this.groupService = groupService;
        this.groupJoinService = groupJoinService;
        this.userService = userService;
        this.pushService = pushService;
    }

    @Override
    public List<String> cmds() {
        return List.of(Cmd.GROUP_SEARCH, Cmd.GROUP_JOIN, Cmd.GROUP_JOIN_REQUESTS,
                Cmd.GROUP_JOIN_HANDLE);
    }

    @Override
    public void handle(ChatContext ctx) {
        switch (ctx.cmd()) {
            case Cmd.GROUP_SEARCH -> search(ctx);
            case Cmd.GROUP_JOIN -> join(ctx);
            case Cmd.GROUP_JOIN_REQUESTS -> requests(ctx);
            case Cmd.GROUP_JOIN_HANDLE -> handleRequest(ctx);
            default -> ctx.fail(404, "未知命令: " + ctx.cmd());
        }
    }

    private void search(ChatContext ctx) {
        GroupSearchReq req = ctx.payload(GroupSearchReq.class);
        if (req.keyword() == null || req.keyword().trim().isEmpty()) {
            throw BizException.badRequest("请输入群号或群名称");
        }
        ctx.reply(Cmd.GROUP_SEARCH_RESP, new GroupSearchResp(groupService.search(req.keyword())));
    }

    private void join(ChatContext ctx) {
        GroupJoinReq req = ctx.payload(GroupJoinReq.class);
        String groupId = req.groupId() == null ? "" : req.groupId().trim();
        User user = userService.byId(ctx.userId())
                .orElseThrow(() -> BizException.unauthorized("账号状态异常"));
        ctx.reply(Cmd.GROUP_JOIN_RESP, groupJoinService.request(groupId, user));
    }

    private void requests(ChatContext ctx) {
        ctx.reply(Cmd.GROUP_JOIN_REQUESTS_RESP,
                new GroupJoinRequestsResp(groupJoinService.listFor(ctx.userId())));
    }

    private void handleRequest(ChatContext ctx) {
        GroupJoinHandleReq req = ctx.payload(GroupJoinHandleReq.class);
        if (req.requestId() == null || req.requestId().isBlank()) {
            throw BizException.badRequest("缺少申请 id");
        }
        ctx.reply(Cmd.GROUP_JOIN_REQUESTS_RESP,
                new GroupJoinRequestsResp(List.of(
                        groupJoinService.handle(req.requestId(), ctx.userId(), req.accept()))));
    }
}
