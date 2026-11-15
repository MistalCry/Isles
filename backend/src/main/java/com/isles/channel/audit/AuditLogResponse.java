package com.isles.channel.audit;

import java.time.Instant;
import java.util.Map;

/**
 * 管理日志条目（GET /channels/{id}/audit-logs）。
 *
 * <p>{@code detail} 为 JSON 字符串（前端按文本展示），避免对象结构耦合。
 */
public record AuditLogResponse(
        String id,
        String channelId,
        String actorId,
        String actorNickname,
        String action,
        String targetType,
        String targetId,
        String detail,
        String ip,
        Instant createdAt) {
}
