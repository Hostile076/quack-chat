package io.quack.server.dispatch.handler;

import io.quack.common.protocol.Cmd;
import io.quack.server.dispatch.ChatContext;
import io.quack.server.dispatch.CommandHandler;
import org.springframework.stereotype.Component;

/**
 * 心跳。客户端每 25s 发一次，服务端立即回。
 *
 * <p>作用有两个：
 * <ol>
 *   <li>穿透 NAT / 代理的空闲连接回收（多数网关 60s 无流量就断）；</li>
 *   <li>让服务端感知“客户端还活着”，后续可据此做半开连接检测与闲置踢人。</li>
 * </ol>
 */
@Component
public class HeartbeatHandler implements CommandHandler {

    @Override
    public String cmd() {
        return Cmd.HEARTBEAT;
    }

    @Override
    public void handle(ChatContext ctx) {
        // touch() 刷新活跃时间；将来做“闲置 30 分钟踢人”就依赖这个字段
        ctx.session().touch();
        ctx.reply(Cmd.HEARTBEAT_RESP, null);
    }
}
