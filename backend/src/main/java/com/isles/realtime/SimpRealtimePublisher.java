package com.isles.realtime;

import com.isles.realtime.dto.RealtimeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(SimpRealtimePublisher.class);

    private static final Set<String> ALLOWED_QUEUES = Set.of(QUEUE_DM, QUEUE_NOTIFICATIONS);
    /** 允许的主题前缀（只读广播）。 */
    private static final String ALLOWED_TOPIC_PREFIX = "/topic/voice/";

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

    @Override
    public void publishTopic(String topic, RealtimeEvent event) {
        if (topic == null || event == null || !topic.startsWith(ALLOWED_TOPIC_PREFIX)) {
            throw new IllegalArgumentException("不支持的实时广播主题: " + topic);
        }
        try {
            messagingTemplate.convertAndSend(topic, event);
        } catch (RuntimeException ex) {
            // 广播是尽力而为通道：失败不影响 REST 结果，客户端重连后按详情接口补状态
            log.warn("实时广播失败 topic={}", topic, ex);
        }
    }
}
