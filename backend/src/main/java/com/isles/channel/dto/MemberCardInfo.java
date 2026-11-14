package com.isles.channel.dto;

import java.time.Instant;

/**
 * 用户在指定频道内的卡片信息（用户卡片浮层用，FR-USER-02）。
 */
public record MemberCardInfo(
        String channelId,
        Instant joinedAt,
        String roleName,
        String memberStatus) {
}
