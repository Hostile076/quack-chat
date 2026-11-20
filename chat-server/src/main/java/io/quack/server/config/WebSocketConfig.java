package io.quack.server.config;

import io.quack.server.ws.ChatWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * WebSocket 配置。
 *
 * <p>三个容易踩的坑，这里都处理了：
 * <ol>
 *   <li><b>消息长度</b>：Tomcat 默认单帧 8KB，长文本/图片 URL 会被直接断开，
 *       所以显式放大到 64KB；</li>
 *   <li><b>空闲超时</b>：容器默认 20s 无数据就断，配合客户端 25s 心跳必须放宽到 120s。</li>
 *   <li><b>跨域</b>：桌面客户端不发 Origin，但用浏览器调试时需要放行。</li>
 * </ol>
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler chatWebSocketHandler;

    public WebSocketConfig(ChatWebSocketHandler chatWebSocketHandler) {
        this.chatWebSocketHandler = chatWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler, "/ws")
                .setAllowedOriginPatterns("*");
    }

    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(64 * 1024);
        container.setMaxBinaryMessageBufferSize(64 * 1024);
        container.setMaxSessionIdleTimeout(120_000L);
        return container;
    }
}
