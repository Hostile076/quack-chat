package io.quack.server.dispatch.handler;

import io.quack.common.dto.RegisterReq;
import io.quack.common.dto.RegisterResp;
import io.quack.common.model.User;
import io.quack.common.protocol.Cmd;
import io.quack.server.dispatch.ChatContext;
import io.quack.server.dispatch.CommandHandler;
import io.quack.server.service.UserService;
import io.quack.server.support.BizException;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 注册：服务端分配 QC 号。
 *
 * <p>用户名允许重复（不同人可以同名），所以注册不再因为重名报 409；
 * 但用户名本身仍限制字符集，避免搜出一堆乱码。
 * 注册成功后客户端要把返回的 QC 号提示给用户 —— 这才是以后登录的唯一凭证。
 */
@Component
public class RegisterHandler implements CommandHandler {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,20}$");

    private final UserService userService;

    public RegisterHandler(UserService userService) {
        this.userService = userService;
    }

    @Override
    public String cmd() {
        return Cmd.REGISTER;
    }

    @Override
    public void handle(ChatContext ctx) {
        RegisterReq req = ctx.payload(RegisterReq.class);
        String username = req.username() == null ? "" : req.username().trim();
        String password = req.password() == null ? "" : req.password();

        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw BizException.badRequest("用户名需为 3~20 位字母、数字或下划线");
        }
        if (password.length() < 6 || password.length() > 32) {
            throw BizException.badRequest("密码长度需为 6~32 位");
        }

        // 昵称不接受客户端指定：默认等于用户名，注册后可在个人主页里改
        User user = userService.register(username, password, null);
        ctx.reply(Cmd.REGISTER_RESP, new RegisterResp(user));
    }
}
