package com.isles.channel.dto;

import java.time.Instant;

/**
 * 频道信息（详情 / 我的频道 / 加入成功响应）。
 */
public record ChannelResponse(
        String id,
        Long channelNo,
        String name,
        String avatarUrl,
        String coverUrl,
        String description,
        String type,
        String visibility,
        String ownerId,
        Long memberCount,
        Long postCount,
        boolean joined,
        String myRoleName,
        Instant createdAt) {
}
