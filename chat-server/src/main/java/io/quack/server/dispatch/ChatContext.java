package io.quack.server.dispatch;

import io.quack.common.protocol.Cmd;
import io.quack.common.protocol.Envelope;
import io.quack.server.support.BizException;
import io.quack.server.ws.ChatSession;

/**
 * 处理一帧请求时的上下文：谁、从哪条连接、发的哪个请求。
 *
 * <p>把“取参数 / 回响应 / 报错”这些样板代码收敛在这里，
 * 具体 Handler 只关心业务逻辑，可读性会好很多。
 */
public class ChatContext {

    private final ChatSession session;
    private final Envelope envelope;

    public ChatContext(ChatSession session, Envelope envelope) {
        this.session = session;
        this.envelope = envelope;
    }

    public ChatSession session() {
        return session;
    }

    public Envelope envelope() {
        return envelope;
    }

    public String cmd() {
        return envelope.cmd();
    }

    public long seq() {
        return envelope.seq();
    }

    /** 当前登录用户；未登录直接抛 401（调用前接入层已拦过，这里是兜底） */
    public String userId() {
        String userId = session.userId();
        if (userId == null) {
            throw BizException.unauthorized("请先登录");
        }
        return userId;
    }

    /** 取业务参数并做空校验 */
    public <T> T payload(Class<T> type) {
        T payload = envelope.dataAs(type);
        if (payload == null) {
            throw BizException.badRequest("缺少请求参数");
        }
        return payload;
    }

    /** 回响应，seq 原样带回，便于客户端配对 */
    public void reply(String cmd, Object data) {
        session.send(Envelope.of(cmd, envelope.seq(), data));
    }

    /**
     * 回错误帧。
     *
     * <p>统一用 {@code error} 命令，而不是“原命令 + 非 0 code”：
     * 这样客户端只需要在一个地方处理错误；请求本身用 {@code seq} 关联，仍然能配对。
     */
    public void fail(int code, String message) {
        session.send(Envelope.error(Cmd.ERROR, envelope.seq(), code, message));
    }
}
