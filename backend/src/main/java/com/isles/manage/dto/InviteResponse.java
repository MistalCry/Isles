package com.isles.manage.dto;

import java.time.Instant;

/**
 * 邀请链接管理列表项（不返回 token 明文，仅创建时返回一次）。
 */
public record InviteResponse(
        String id,
        String channelId,
        boolean requiresApproval,
        Instant expiresAt,
        Integer maxUses,
        Integer usedCount,
        boolean revoked,
        String status,
        String createdBy,
        Instant createdAt) {
}
