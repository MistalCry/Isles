package com.isles.analytics.dto;

import java.math.BigDecimal;

/**
 * 互动漏斗：浏览 → 点赞 → 评论 → 分享。
 */
public record FunnelStatsResponse(
        long viewCount,
        long likeCount,
        long commentCount,
        long shareCount,
        BigDecimal interactionRate) {
}
