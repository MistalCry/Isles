package com.isles.channel.dto;

import java.time.Instant;

/**
 * 邀请链接创建响应：token 明文仅在此刻返回一次，数据库只存哈希。
 */
public record InviteCreateResponse(
        String id,
        String token,
        String url,
        boolean requiresApproval,
        Instant expiresAt,
        Integer maxUses) {
}
