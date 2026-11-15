package com.isles.realtime;

import com.isles.realtime.dto.RealtimeEvent;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * {@link RealtimePublisher} 的 STOMP 实现：通过 {@link SimpMessagingTemplate} 向
 * 已绑定的用户目的地（{@code /user/{userId}/queue/...}）发送事件。
 *
 * <p>Principal 由 {@link StompAuthChannelInterceptor} 在 CONNECT 帧绑定为 userId，
 * 因此 {@code convertAndSendToUser(userId, ...)} 能精确定位到该用户的所有在线会话（多端同时在线均可收到）。
 */
@Component
public class SimpRealtimePublisher implements RealtimePublisher {

    private static final Set<String> ALLOWED_QUEUES = Set.of(QUEUE_DM, QUEUE_NOTIFICATIONS);

    private final SimpMessagingTemplate messagingTemplate;

    public SimpRealtimePublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    public void publishDm(String userId, RealtimeEvent event) {
        publishToUser(userId, QUEUE_DM, event);
    }

    @Override
    public void publishNotification(String userId, RealtimeEvent event) {
        publishToUser(userId, QUEUE_NOTIFICATIONS, event);
    }

    @Override
    public void publishToUser(String userId, String queue, RealtimeEvent event) {
        if (userId == null || userId.isBlank() || event == null) {
            return;
        }
        if (!ALLOWED_QUEUES.contains(queue)) {
            throw new IllegalArgumentException("不支持的实时推送队列: " + queue);
        }
        messagingTemplate.convertAndSendToUser(userId, queue, event);
    }
}
