package com.isles.analytics.dto;

import java.util.List;

/**
 * 内容分析：板块分布 + Top 帖子 + 互动漏斗。
 */
public record ContentStatsResponse(
        List<BoardStatResponse> boardDistribution,
        List<TopPostStatResponse> topPosts,
        FunnelStatsResponse funnel) {
}
