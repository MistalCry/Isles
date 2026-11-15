package com.isles.config;

import com.isles.realtime.StompAuthChannelInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

import java.util.Arrays;

/**
 * STOMP over WebSocket 配置（docs/02 §5.4、ADR-0003）：
 * <ul>
 *   <li>端点 {@code /ws}，原生 WebSocket（不启用 SockJS）；</li>
 *   <li>CONNECT 帧鉴权见 {@link StompAuthChannelInterceptor}（Principal = userId）；</li>
 *   <li>下行队列 {@code /user/queue/dm}、{@code /user/queue/notifications}；</li>
 *   <li>心跳 10s/10s、单帧 16KB、发送缓冲与超时有上限（docs/00 §10 背压与载荷约束）。</li>
 * </ul>
 * 写路径仍走 REST：WebSocket 只做服务端下行推送，断线后客户端按游标补拉。
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /** 心跳间隔：服务端与客户端均为 10s（docs/02 §5.4）。 */
    private static final long HEARTBEAT_MILLIS = 10_000L;
    /** 单条 STOMP 帧上限 16KB（docs/00 §10）。 */
    private static final int MESSAGE_SIZE_LIMIT = 16 * 1024;
    /** 每个连接发送缓冲上限，超限断开（docs/00 §10 背压）。 */
    private static final int SEND_BUFFER_SIZE_LIMIT = 512 * 1024;
    /** 单次发送超时，避免慢客户端拖住会话。 */
    private static final int SEND_TIME_LIMIT_MILLIS = 10_000;

    private final StompAuthChannelInterceptor authChannelInterceptor;
    private final String[] allowedOrigins;

    public WebSocketConfig(StompAuthChannelInterceptor authChannelInterceptor,
                           @Value("${app.cors.allowed-origins:http://localhost:5173}") String allowedOrigins) {
        this.authChannelInterceptor = authChannelInterceptor;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toArray(String[]::new);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 原生 WebSocket：浏览器无法在握手请求上设置 Authorization 头，鉴权改在 CONNECT 帧完成
        registry.addEndpoint("/ws").setAllowedOriginPatterns(allowedOrigins);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/queue", "/topic")
                .setHeartbeatValue(new long[]{HEARTBEAT_MILLIS, HEARTBEAT_MILLIS})
                .setTaskScheduler(heartbeatScheduler());
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authChannelInterceptor);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(MESSAGE_SIZE_LIMIT);
        registration.setSendBufferSizeLimit(SEND_BUFFER_SIZE_LIMIT);
        registration.setSendTimeLimit(SEND_TIME_LIMIT_MILLIS);
    }

    /**
     * 心跳调度器：单线程 daemon，避免占用 Web 线程池。
     */
    @Bean
    public ThreadPoolTaskScheduler heartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.setDaemon(true);
        return scheduler;
    }
}
