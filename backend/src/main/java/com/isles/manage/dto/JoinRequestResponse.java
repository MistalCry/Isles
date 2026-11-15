package com.isles.manage.dto;

import java.time.Instant;

/**
 * 加入申请（审批列表）。
 */
public record JoinRequestResponse(
        String id,
        String channelId,
        String userId,
        String username,
        String nickname,
        String avatarUrl,
        String inviteId,
        String status,
        String reviewedBy,
        String reviewerNickname,
        Instant reviewedAt,
        String reviewNote,
        Instant createdAt) {
}
