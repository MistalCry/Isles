package com.isles.realtime;

import com.isles.auth.service.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * STOMP 连接鉴权（docs/02 §5.4、docs/07 §2）：
 * <ul>
 *   <li>CONNECT 帧必须携带 {@code Authorization: Bearer <accessToken>}，校验通过后把 Principal 绑定为 userId；</li>
 *   <li>SUBSCRIBE 仅允许个人队列 {@code /user/queue/dm}、{@code /user/queue/notifications}，
 *       且必须已通过 CONNECT 鉴权（按用户隔离，避免越权订阅他人私信）；</li>
 *   <li>SEND 一律拒绝：写路径统一走 REST，WebSocket 只做下行推送（docs/02 §5.4）。</li>
 * </ul>
 * 鉴权失败抛 {@link MessagingException}，由 STOMP 协议层回 ERROR 帧并断开连接。
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompAuthChannelInterceptor.class);
    private static final String AUTHORIZATION = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    /** 用户目的地前缀：浏览器订阅的是 {@code /user/queue/dm}，服务端下行队列是 {@code /queue/dm}。 */
    private static final String USER_PREFIX = "/user";
    private static final Set<String> ALLOWED_SUBSCRIPTIONS = Set.of(
            RealtimePublisher.QUEUE_DM, RealtimePublisher.QUEUE_NOTIFICATIONS);

    private final JwtService jwtService;

    public StompAuthChannelInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            // 心跳等无 STOMP 命令的帧直接放行
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (StompCommand.CONNECT.equals(command)) {
            authenticate(accessor);
        } else if (StompCommand.SUBSCRIBE.equals(command)) {
            requireAuthenticated(accessor);
            String destination = accessor.getDestination();
            if (!isAllowedSubscription(destination)) {
                throw new MessagingException("不允许订阅该目的地: " + destination);
            }
        } else if (StompCommand.SEND.equals(command)) {
            throw new MessagingException("客户端不支持经 WebSocket 发送消息，请使用 REST 接口");
        }
        return message;
    }

    /**
     * 允许的订阅：必须是用户级目的地 {@code /user/queue/dm|notifications}。
     *
     * <p>客户端按 Spring 约定订阅 {@code /user/queue/dm}，而 {@link RealtimePublisher} 的常量是
     * 服务端下行队列 {@code /queue/dm}；这里先剥离 {@code /user} 前缀再比对，避免两边写法差异导致
     * 所有实时订阅被拒（曾导致私信/通知实时推送完全不可用）。
     */
    private boolean isAllowedSubscription(String destination) {
        if (destination == null || !destination.startsWith(USER_PREFIX + "/")) {
            return false;
        }
        String normalized = destination.substring(USER_PREFIX.length());
        return ALLOWED_SUBSCRIPTIONS.contains(normalized);
    }

    private void authenticate(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader(AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw new MessagingException("CONNECT 帧缺少 Authorization: Bearer 令牌");
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            throw new MessagingException("CONNECT 帧的访问令牌为空");
        }
        String userId;
        try {
            userId = jwtService.parseUserId(token);
        } catch (RuntimeException ex) {
            // 不带原始异常信息给客户端，避免泄露令牌解析细节
            throw new MessagingException("访问令牌无效或已过期", ex);
        }
        if (userId == null || userId.isBlank()) {
            throw new MessagingException("访问令牌缺少用户标识");
        }
        accessor.setUser(new StompPrincipal(userId));
        log.debug("WebSocket 连接鉴权成功 userId={}", userId);
    }

    private void requireAuthenticated(StompHeaderAccessor accessor) {
        if (accessor.getUser() == null) {
            throw new MessagingException("订阅前必须先完成 CONNECT 鉴权");
        }
    }
}
