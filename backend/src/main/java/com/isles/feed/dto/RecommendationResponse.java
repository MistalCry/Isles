package com.isles.feed.dto;

/**
 * 频道推荐（FR-HOT-03）：频道信息 + 推荐理由。
 */
public record RecommendationResponse(
        String channelId,
        Long channelNo,
        String name,
        String avatarUrl,
        String coverUrl,
        String description,
        Long memberCount,
        Long postCount,
        double score,
        String reason) {
}
