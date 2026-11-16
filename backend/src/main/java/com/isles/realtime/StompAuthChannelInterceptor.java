package com.isles.realtime;

import com.isles.apps.service.VoiceRoomService;
import com.isles.auth.service.JwtService;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * STOMP 连接鉴权与目的地白名单（docs/02 §5.4、docs/07 §2、docs/05 §2.14）：
 * <ul>
 *   <li>CONNECT 帧必须携带 {@code Authorization: Bearer <accessToken>}，校验通过后把 Principal 绑定为 userId；</li>
 *   <li>SUBSCRIBE 允许两类目的地：
 *     <ul>
 *       <li>个人队列 {@code /user/queue/dm}、{@code /user/queue/notifications}（按用户隔离）；</li>
 *       <li>语音房主题 {@code /topic/voice/{roomId}/signal|state}（**需在房**）。</li>
 *     </ul>
 *   </li>
 *   <li>SEND 允许语音房信令 {@code /app/voice/{roomId}/signal}（**需在房**），其余一律拒绝：
 *       业务写路径统一走 REST，WebSocket 只承担实时推送与语音信令转发。</li>
 * </ul>
 *
 * <p>「在房」的判定见 {@link VoiceRoomService#requireSignalAccess}：房间 OPEN 且（在麦位上 或 走过 join 登记）。
 * 拒绝时抛 {@link StompAccessDeniedException}，由 {@link StompErrorFrameHandler} 回带 {@code code} 的 ERROR 帧。
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

    /** 语音房订阅：{@code /topic/voice/{roomId}/signal|state}。 */
    private static final Pattern VOICE_TOPIC = Pattern.compile("^/topic/voice/([^/]+)/(signal|state)$");
    /** 语音房信令发送：{@code /app/voice/{roomId}/signal}。 */
    private static final Pattern VOICE_SEND = Pattern.compile("^/app/voice/([^/]+)/signal$");

    private final JwtService jwtService;
    /**
     * 用 {@link ObjectProvider} 延迟取：语音房服务依赖实时发布器，实时发布器又依赖消息模板，
     * 直接构造注入会让 WebSocket 配置与消息基础设施互相等待启动。
     */
    private final ObjectProvider<VoiceRoomService> voiceRoomServiceProvider;

    public StompAuthChannelInterceptor(JwtService jwtService,
                                       ObjectProvider<VoiceRoomService> voiceRoomServiceProvider) {
        this.jwtService = jwtService;
        this.voiceRoomServiceProvider = voiceRoomServiceProvider;
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
            if (isAllowedSubscription(destination)) {
                return message;
            }
            Matcher voice = destination == null ? null : VOICE_TOPIC.matcher(destination);
            if (voice != null && voice.matches()) {
                requireVoiceAccess(accessor, voice.group(1));
                return message;
            }
            throw new MessagingException("不允许订阅该目的地: " + destination);
        } else if (StompCommand.SEND.equals(command)) {
            String destination = accessor.getDestination();
            Matcher signal = destination == null ? null : VOICE_SEND.matcher(destination);
            if (signal != null && signal.matches()) {
                requireAuthenticated(accessor);
                requireVoiceAccess(accessor, signal.group(1));
                return message;
            }
            throw new MessagingException("客户端不支持经 WebSocket 发送消息，请使用 REST 接口");
        }
        return message;
    }

    /**
     * 语音房目的地校验：必须是已鉴权连接，且「在房」。
     *
     * <p>订阅与发送共用本方法——只校验发送会导致非成员直接订阅别人的房间主题，
     * 白听信令（含 SDP 与 ICE），等于绕过鉴权。
     */
    private void requireVoiceAccess(StompHeaderAccessor accessor, String roomId) {
        String userId = accessor.getUser() == null ? null : accessor.getUser().getName();
        if (userId == null || userId.isBlank()) {
            throw new StompAccessDeniedException("UNAUTHORIZED", "语音房信令需要先完成 CONNECT 鉴权");
        }
        VoiceRoomService voiceRoomService = voiceRoomServiceProvider.getIfAvailable();
        if (voiceRoomService == null) {
            throw new StompAccessDeniedException("ROOM_CLOSED", "语音房服务不可用");
        }
        try {
            voiceRoomService.requireSignalAccess(roomId, userId);
        } catch (AppException ex) {
            String code = stompCode(ex.getErrorCode());
            log.debug("语音房信令被拒绝 roomId={} userId={} code={}", roomId, userId, code);
            throw new StompAccessDeniedException(code, ex.getMessage());
        }
    }

    /**
     * REST 错误码 → STOMP 错误码。
     *
     * <p>语音房这两个码在 STOMP 侧用更短的名字（{@code NOT_IN_ROOM} / {@code ROOM_CLOSED}），
     * 与前端约定的字面量一致；其余沿用枚举名，保证同一套语义不出现两种拼写。
     */
    private String stompCode(ErrorCode errorCode) {
        return switch (errorCode) {
            case VOICE_NOT_IN_ROOM -> "NOT_IN_ROOM";
            case VOICE_ROOM_CLOSED -> "ROOM_CLOSED";
            default -> errorCode.name();
        };
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
