package com.isles.manage.dto;

import java.time.Instant;

/**
 * 成员管理列表项。
 */
public record MemberResponse(
        String userId,
        String username,
        String nickname,
        String avatarUrl,
        String roleId,
        String roleName,
        String status,
        Instant mutedUntil,
        boolean muted,
        Instant joinedAt) {
}
