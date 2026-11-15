package com.isles.manage.dto;

import java.time.Instant;

/**
 * 举报记录（含目标摘要与处置结果）。
 */
public record ReportResponse(
        String id,
        String reporterId,
        String reporterNickname,
        String channelId,
        String channelName,
        String targetType,
        String targetId,
        String targetPreview,
        String targetAuthorId,
        String targetAuthorNickname,
        String reason,
        String description,
        String status,
        String handleAction,
        String handleNote,
        String handlerId,
        String handlerNickname,
        Instant handledAt,
        Instant createdAt) {
}
