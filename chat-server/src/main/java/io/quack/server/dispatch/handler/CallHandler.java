package io.quack.server.dispatch.handler;

import io.quack.common.dto.CallReq;
import io.quack.common.model.CallSignal;
import io.quack.common.model.User;
import io.quack.common.protocol.Cmd;
import io.quack.server.dispatch.ChatContext;
import io.quack.server.dispatch.MultiCommandHandler;
import io.quack.server.service.CallService;
import io.quack.server.service.UserService;
import io.quack.server.support.BizException;
import org.springframework.stereotype.Component;

import java.util.List;

/** 通话信令：发起 / 接听 / 拒绝 / 挂断 */
@Component
public class CallHandler implements MultiCommandHandler {

    private final CallService callService;
    private final UserService userService;

    public CallHandler(CallService callService, UserService userService) {
        this.callService = callService;
        this.userService = userService;
    }

    @Override
    public List<String> cmds() {
        return List.of(Cmd.CALL_INVITE, Cmd.CALL_ACCEPT, Cmd.CALL_REJECT, Cmd.CALL_HANGUP,
                Cmd.CALL_AUDIO);
    }

    @Override
    public void handle(ChatContext ctx) {
        if (Cmd.CALL_AUDIO.equals(ctx.cmd())) {
            io.quack.common.dto.CallAudioReq audio = ctx.payload(io.quack.common.dto.CallAudioReq.class);
            callService.relayAudio(ctx.userId(), audio.callId(), audio.data());
            return;
        }
        CallReq req = ctx.payload(CallReq.class);
        User me = userService.byId(ctx.userId())
                .orElseThrow(() -> BizException.unauthorized("账号状态异常"));

        CallSignal signal = switch (ctx.cmd()) {
            case Cmd.CALL_INVITE -> callService.invite(me, req.toId());
            case Cmd.CALL_ACCEPT -> callService.respond(me.id(), req.callId(), CallSignal.ACCEPT);
            case Cmd.CALL_REJECT -> callService.respond(me.id(), req.callId(), CallSignal.REJECT);
            default -> callService.respond(me.id(), req.callId(), CallSignal.HANGUP);
        };
        // 回包命令要和动作语义一致：发起呼叫不能回「已接听」
        String replyCmd = switch (ctx.cmd()) {
            case Cmd.CALL_INVITE -> Cmd.CALL_INVITED;
            case Cmd.CALL_ACCEPT -> Cmd.CALL_ANSWERED;
            case Cmd.CALL_REJECT -> Cmd.CALL_REJECTED;
            default -> Cmd.CALL_ENDED;
        };
        ctx.reply(replyCmd, signal);
    }
}
