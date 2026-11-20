package io.quack.server.dispatch.handler;

import io.quack.common.dto.LikeReq;
import io.quack.common.dto.LikeResp;
import io.quack.common.protocol.Cmd;
import io.quack.server.dispatch.ChatContext;
import io.quack.server.dispatch.CommandHandler;
import io.quack.server.service.UserService;
import io.quack.server.support.BizException;
import org.springframework.stereotype.Component;

/** 资料页点赞 */
@Component
public class LikeHandler implements CommandHandler {

    private final UserService userService;

    public LikeHandler(UserService userService) {
        this.userService = userService;
    }

    @Override
    public String cmd() {
        return Cmd.PROFILE_LIKE;
    }

    @Override
    public void handle(ChatContext ctx) {
        LikeReq req = ctx.payload(LikeReq.class);
        String target = req.qcId() == null ? "" : req.qcId().trim();
        if (target.isEmpty()) {
            throw BizException.badRequest("缺少对方 QC 号");
        }
        if (target.equals(ctx.userId())) {
            throw BizException.badRequest("不能给自己点赞");
        }
        int likes = userService.like(target);
        ctx.reply(Cmd.PROFILE_LIKE_RESP, new LikeResp(Long.parseLong(target), likes));
    }
}
