package com.isles.notification.dto;

import java.time.Instant;

/**
 * 通知条目（GET /notifications，docs/05 §2.7 M1 基础）。
 */
public record NotificationResponse(
        String id,
        String type,
        String channelId,
        String postId,
        String commentId,
        String actorId,
        String actorNickname,
        String title,
        String content,
        boolean read,
        Instant createdAt) {
}
