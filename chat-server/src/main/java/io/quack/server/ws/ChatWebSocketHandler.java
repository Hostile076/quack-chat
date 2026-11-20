package io.quack.server.ws;

import io.quack.common.protocol.Cmd;
import io.quack.common.protocol.Envelope;
import io.quack.common.protocol.JsonCodec;
import io.quack.server.dispatch.ChatContext;
import io.quack.server.dispatch.CommandDispatcher;
import io.quack.server.service.PresenceService;
import io.quack.server.support.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * WebSocket 接入层。只做四件事：
 * <ol>
 *   <li>连接建立时登记会话；</li>
 *   <li>收到文本帧 —— 解析信封、校验登录态、交给 Dispatcher；</li>
 *   <li>统一异常处理，把异常翻译成错误帧（绝不让异常穿透到容器）；</li>
 *   <li>连接关闭时清理会话并广播下线。</li>
 * </ol>
 *
 * <p>业务逻辑一行都不写在这里 —— 这是可扩展性的关键。
 */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    private final SessionManager sessionManager;
    private final CommandDispatcher dispatcher;
    private final PresenceService presenceService;
    private final io.quack.server.service.RateLimiter rateLimiter;

    public ChatWebSocketHandler(SessionManager sessionManager, CommandDispatcher dispatcher,
                                PresenceService presenceService,
                                io.quack.server.service.RateLimiter rateLimiter) {
        this.sessionManager = sessionManager;
        this.dispatcher = dispatcher;
        this.presenceService = presenceService;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        ChatSession chatSession = sessionManager.open(session);
        log.debug("连接建立 session={} 当前在线={}", chatSession.id(), sessionManager.onlineCount());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        ChatSession chatSession = sessionManager.bySessionId(session.getId());
        if (chatSession == null) {
            return;
        }
        chatSession.touch();

        Envelope envelope = JsonCodec.parse(message.getPayload());
        if (envelope == null) {
            chatSession.send(Envelope.error(Cmd.ERROR, 0, 400, "报文格式错误，期望 JSON"));
            return;
        }
        // 登录态校验放在这一层，Handler 里就不用各自判断了。
        // 注册也必须放行 —— 它是“还没有身份”时唯一合法的业务命令。
        if (!chatSession.isAuthed()
                && !Cmd.LOGIN.equals(envelope.cmd())
                && !Cmd.REGISTER.equals(envelope.cmd())) {
            chatSession.send(Envelope.error(Cmd.ERROR, envelope.seq(), 401, "请先登录"));
            return;
        }

        ChatContext ctx = new ChatContext(chatSession, envelope);
        try {
            // 单连接消息限流：防止一个客户端刷爆服务端/对方
            if (Cmd.MSG_SEND.equals(envelope.cmd()) && !rateLimiter.allowMessage(chatSession.id())) {
                ctx.fail(429, "发送太快了，请稍后再试");
                return;
            }
            dispatcher.dispatch(ctx);
        } catch (BizException e) {
            ctx.fail(e.code(), e.getMessage());
        } catch (IllegalArgumentException e) {
            ctx.fail(400, "参数错误: " + e.getMessage());
        } catch (Exception e) {
            log.error("处理命令失败 session={} cmd={}", chatSession.id(), envelope.cmd(), e);
            ctx.fail(500, "服务器内部错误");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        SessionManager.CloseResult result = sessionManager.close(session.getId());
        rateLimiter.forgetSession(session.getId());
        if (result != null && result.lastDevice()) {
            // 只有这个账号的最后一台设备断开，才对好友广播离线
            presenceService.offline(result.userId());
        }
        if (result != null) {
            log.info("设备下线 user={} 当前在线连接={}", result.userId(), sessionManager.onlineCount());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        // 网络抖动/客户端崩溃会走到这里，后面的 afterConnectionClosed 会负责清理
        log.warn("连接异常 session={}: {}", session.getId(), exception.getMessage());
    }
}
