package com.isles.notification.dto;

import java.time.Instant;
import java.util.Map;

/**
 * 通知条目（docs/05 §2.7 增强版）：分类、跳转 payload、已读时间。
 */
public record NotificationResponse(
        String id,
        String type,
        String category,
        String channelId,
        String postId,
        String commentId,
        String actorId,
        String actorNickname,
        String title,
        String content,
        Map<String, Object> payload,
        boolean read,
        Instant readAt,
        Instant createdAt) {
}
