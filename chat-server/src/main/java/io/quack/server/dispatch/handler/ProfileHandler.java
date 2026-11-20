package io.quack.server.dispatch.handler;

import io.quack.common.dto.ProfileReq;
import io.quack.common.dto.ProfileResp;
import io.quack.common.model.UserProfile;
import io.quack.common.protocol.Cmd;
import io.quack.server.dispatch.ChatContext;
import io.quack.server.dispatch.CommandHandler;
import io.quack.server.service.UserService;
import io.quack.server.support.BizException;
import org.springframework.stereotype.Component;

/** 查看资料：不传 qcId 就是看自己 */
@Component
public class ProfileHandler implements CommandHandler {

    private final UserService userService;

    public ProfileHandler(UserService userService) {
        this.userService = userService;
    }

    @Override
    public String cmd() {
        return Cmd.PROFILE;
    }

    @Override
    public void handle(ChatContext ctx) {
        // 不传参数 = 看自己，所以这里不能强制要求 payload
        ProfileReq req = ctx.envelope().dataAs(ProfileReq.class);
        String target = req == null || req.qcId() == null || req.qcId().isBlank()
                ? ctx.userId() : req.qcId().trim();
        UserProfile profile = userService.profile(target)
                .orElseThrow(() -> new BizException(404, "用户不存在：" + target));
        ctx.reply(Cmd.PROFILE_RESP, new ProfileResp(profile));
    }
}
