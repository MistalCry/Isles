package com.isles.realtime;

import com.isles.apps.service.VoiceRoomService;
import com.isles.auth.service.JwtService;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.oauth2.jwt.JwtException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * STOMP CONNECT 鉴权与订阅白名单单元测试（Mockito，不建立真实 WebSocket 连接）。
 */
class StompAuthChannelInterceptorTest {

    private JwtService jwtService;
    private VoiceRoomService voiceRoomService;
    private StompAuthChannelInterceptor interceptor;
    private MessageChannel channel;

    @BeforeEach
    void setUp() {
        jwtService = mock(JwtService.class);
        voiceRoomService = mock(VoiceRoomService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<VoiceRoomService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(voiceRoomService);
        interceptor = new StompAuthChannelInterceptor(jwtService, provider);
        channel = mock(MessageChannel.class);
    }

    @Test
    void should_bind_principal_when_connect_with_valid_bearer_token() {
        when(jwtService.parseUserId("token-1")).thenReturn("user-1");
        Message<?> result = interceptor.preSend(connectMessage("Bearer token-1"), channel);

        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(result, StompHeaderAccessor.class);
        assertThat(accessor).isNotNull();
        assertThat(accessor.getUser()).isNotNull();
        assertThat(accessor.getUser().getName()).isEqualTo("user-1");
    }

    @Test
    void should_reject_connect_without_authorization_header() {
        assertThatThrownBy(() -> interceptor.preSend(connectMessage(null), channel))
                .isInstanceOf(MessagingException.class)
                .hasMessageContaining("Authorization");
    }

    @Test
    void should_reject_connect_with_invalid_token() {
        when(jwtService.parseUserId("expired")).thenThrow(new JwtException("expired"));
        assertThatThrownBy(() -> interceptor.preSend(connectMessage("Bearer expired"), channel))
                .isInstanceOf(MessagingException.class)
                .hasMessageContaining("访问令牌无效");
    }

    @Test
    void should_allow_subscribe_to_user_dm_queue() {
        // 浏览器实际订阅的是 /user/queue/dm（Spring 用户目的地约定）
        Message<?> result = interceptor.preSend(
                subscribeMessage("/user" + RealtimePublisher.QUEUE_DM, "user-1"), channel);
        assertThat(result).isNotNull();
    }

    @Test
    void should_allow_subscribe_to_notifications_queue() {
        Message<?> result = interceptor.preSend(
                subscribeMessage("/user" + RealtimePublisher.QUEUE_NOTIFICATIONS, "user-1"), channel);
        assertThat(result).isNotNull();
    }

    @Test
    void should_reject_subscribe_to_shared_queue_without_user_prefix() {
        // 裸 /queue/dm 是共享目的地，订阅它无法按用户隔离，必须拒绝
        assertThatThrownBy(() -> interceptor.preSend(subscribeMessage(RealtimePublisher.QUEUE_DM, "user-1"),
                channel))
                .isInstanceOf(MessagingException.class)
                .hasMessageContaining("不允许订阅");
    }

    @Test
    void should_reject_subscribe_to_other_destination() {
        assertThatThrownBy(() -> interceptor.preSend(subscribeMessage("/topic/channels/ch-1", "user-1"), channel))
                .isInstanceOf(MessagingException.class)
                .hasMessageContaining("不允许订阅");
    }

    @Test
    void should_reject_subscribe_without_principal() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/user" + RealtimePublisher.QUEUE_DM);
        accessor.setLeaveMutable(true);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(MessagingException.class)
                .hasMessageContaining("CONNECT 鉴权");
    }

    @Test
    void should_reject_client_send_frames() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/app/dm");
        accessor.setUser(new StompPrincipal("user-1"));
        accessor.setLeaveMutable(true);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(MessagingException.class)
                .hasMessageContaining("REST");
    }


    // ------------------------------------------------------------------
    // 语音房信令（K1）：订阅与发送两条路径都要校验在房
    // ------------------------------------------------------------------

    @Test
    void should_allow_voice_signal_subscribe_when_user_in_room() {
        Message<?> result = interceptor.preSend(
                subscribeMessage("/topic/voice/room-1/signal", "user-1"), channel);
        assertThat(result).isNotNull();
        verify(voiceRoomService).requireSignalAccess("room-1", "user-1");
    }

    @Test
    void should_allow_voice_state_subscribe_when_user_in_room() {
        Message<?> result = interceptor.preSend(
                subscribeMessage("/topic/voice/room-1/state", "user-1"), channel);
        assertThat(result).isNotNull();
        verify(voiceRoomService).requireSignalAccess("room-1", "user-1");
    }

    @Test
    void should_reject_voice_subscribe_when_not_in_room_with_code() {
        doThrow(new AppException(ErrorCode.VOICE_NOT_IN_ROOM))
                .when(voiceRoomService).requireSignalAccess("room-1", "user-1");

        assertThatThrownBy(() -> interceptor.preSend(
                subscribeMessage("/topic/voice/room-1/signal", "user-1"), channel))
                .isInstanceOf(StompAccessDeniedException.class)
                .satisfies(ex -> assertThat(((StompAccessDeniedException) ex).getCode())
                        .isEqualTo("NOT_IN_ROOM"));
    }

    @Test
    void should_reject_voice_subscribe_when_room_closed_with_code() {
        doThrow(new AppException(ErrorCode.VOICE_ROOM_CLOSED))
                .when(voiceRoomService).requireSignalAccess("room-1", "user-1");

        assertThatThrownBy(() -> interceptor.preSend(
                subscribeMessage("/topic/voice/room-1/state", "user-1"), channel))
                .isInstanceOf(StompAccessDeniedException.class)
                .satisfies(ex -> assertThat(((StompAccessDeniedException) ex).getCode())
                        .isEqualTo("ROOM_CLOSED"));
    }

    @Test
    void should_allow_voice_signal_send_when_user_in_room() {
        Message<?> result = interceptor.preSend(
                sendMessage("/app/voice/room-1/signal", "user-1"), channel);
        assertThat(result).isNotNull();
        verify(voiceRoomService).requireSignalAccess("room-1", "user-1");
    }

    @Test
    void should_reject_voice_signal_send_when_not_in_room() {
        doThrow(new AppException(ErrorCode.VOICE_NOT_IN_ROOM))
                .when(voiceRoomService).requireSignalAccess("room-1", "user-1");

        assertThatThrownBy(() -> interceptor.preSend(
                sendMessage("/app/voice/room-1/signal", "user-1"), channel))
                .isInstanceOf(StompAccessDeniedException.class)
                .satisfies(ex -> assertThat(((StompAccessDeniedException) ex).getCode())
                        .isEqualTo("NOT_IN_ROOM"));
    }

    @Test
    void should_reject_other_app_send_destinations() {
        assertThatThrownBy(() -> interceptor.preSend(sendMessage("/app/dm", "user-1"), channel))
                .isInstanceOf(MessagingException.class)
                .hasMessageContaining("REST");
        verify(voiceRoomService, never()).requireSignalAccess(anyString(), anyString());
    }

    private Message<byte[]> connectMessage(String authorization) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        if (authorization != null) {
            accessor.setNativeHeader("Authorization", authorization);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<byte[]> subscribeMessage(String destination, String userId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setUser(new StompPrincipal(userId));
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<byte[]> sendMessage(String destination, String userId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination(destination);
        accessor.setUser(new StompPrincipal(userId));
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
