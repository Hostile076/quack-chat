package io.quack.server.web;

import io.quack.server.service.Metrics;
import io.quack.server.store.MessageStore;
import io.quack.server.ws.SessionManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 健康检查与最小指标：部署、容器探针和压测时用。 */
@RestController
public class OpsController {

    private final SessionManager sessionManager;
    private final MessageStore messageStore;
    private final Metrics metrics;

    public OpsController(SessionManager sessionManager, MessageStore messageStore, Metrics metrics) {
        this.sessionManager = sessionManager;
        this.messageStore = messageStore;
        this.metrics = metrics;
    }

    @GetMapping("/healthz")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("uptimeSeconds", metrics.uptimeSeconds());
        body.put("online", sessionManager.onlineCount());
        return body;
    }

    /** Prometheus 文本格式；没有引入额外依赖，格式保持兼容 */
    @GetMapping(value = "/metrics", produces = "text/plain; charset=utf-8")
    public String metrics() {
        StringBuilder text = new StringBuilder();
        text.append("# HELP quack_online_connections 当前在线连接数\n");
        text.append("# TYPE quack_online_connections gauge\n");
        text.append("quack_online_connections ").append(sessionManager.onlineCount()).append('\n');
        text.append("# HELP quack_messages_total 进程启动以来接受的消息数\n");
        text.append("# TYPE quack_messages_total counter\n");
        text.append("quack_messages_total ").append(metrics.messagesAccepted()).append('\n');
        text.append("# HELP quack_messages_delivered_total 补发/投递的消息数\n");
        text.append("# TYPE quack_messages_delivered_total counter\n");
        text.append("quack_messages_delivered_total ").append(metrics.messagesDelivered()).append('\n');
        text.append("# HELP quack_messages_stored 内存中保留的消息数\n");
        text.append("# TYPE quack_messages_stored gauge\n");
        text.append("quack_messages_stored ").append(messageStore.count()).append('\n');
        text.append("# HELP quack_uptime_seconds 进程运行时长\n");
        text.append("# TYPE quack_uptime_seconds gauge\n");
        text.append("quack_uptime_seconds ").append(metrics.uptimeSeconds()).append('\n');
        return text.toString();
    }
}
