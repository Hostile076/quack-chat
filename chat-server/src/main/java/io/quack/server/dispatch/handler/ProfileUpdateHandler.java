package io.quack.server.dispatch.handler;

import io.quack.common.dto.ProfileResp;
import io.quack.common.dto.UpdateProfileReq;
import io.quack.common.model.UserProfile;
import io.quack.common.protocol.Cmd;
import io.quack.server.dispatch.ChatContext;
import io.quack.server.dispatch.CommandHandler;
import io.quack.server.service.UserService;
import org.springframework.stereotype.Component;

/** 修改自己的资料（昵称、签名、性别、生日、所在地、身份） */
@Component
public class ProfileUpdateHandler implements CommandHandler {

    private final UserService userService;
    private final io.quack.server.service.PushService pushService;

    public ProfileUpdateHandler(UserService userService,
                                io.quack.server.service.PushService pushService) {
        this.userService = userService;
        this.pushService = pushService;
    }

    @Override
    public String cmd() {
        return Cmd.PROFILE_UPDATE;
    }

    @Override
    public void handle(ChatContext ctx) {
        UpdateProfileReq req = ctx.payload(UpdateProfileReq.class);
        UserProfile profile = userService.updateProfile(ctx.userId(), req);
        ctx.reply(Cmd.PROFILE_UPDATE_RESP, new ProfileResp(profile));
        // 把新资料推给在线好友，别人不用重新登录就能看到新头像/昵称
        pushService.broadcastUser(new io.quack.common.model.User(
                profile.qcId(), profile.username(), profile.displayName(), profile.avatar()),
                ctx.userId());
    }
}
