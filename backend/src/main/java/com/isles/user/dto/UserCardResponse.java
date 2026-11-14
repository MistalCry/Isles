package com.isles.user.dto;

import java.time.Instant;

/**
 * 用户卡片（FR-USER-02）：可带 channelId 返回加入时间与身份组。
 */
public record UserCardResponse(
        String id,
        String username,
        String nickname,
        String avatarUrl,
        String signature,
        String gender,
        String region,
        String channelId,
        Instant joinedAt,
        String roleName,
        String memberStatus) {
}
