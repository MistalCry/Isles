package com.isles.platform.audit;

import java.time.Instant;

/**
 * 审计日志展示模型（后台列表）。
 *
 * @param detail 原始 JSON 文本，前端按需展开；不解析成对象以免契约随业务字段漂移
 */
public record PlatformAuditLogView(
        String id,
        String actorId,
        String actorName,
        String action,
        String targetType,
        String targetId,
        String detail,
        String ip,
        String userAgent,
        Instant createdAt) {
}
