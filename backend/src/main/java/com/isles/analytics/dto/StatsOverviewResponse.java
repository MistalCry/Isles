package com.isles.analytics.dto;

/**
 * 数据中心概览卡片：实时数字 + 昨日指标 + 环比差值。
 */
public record StatsOverviewResponse(
        String channelId,
        long memberCount,
        long postCount,
        DailyStatsResponse yesterday,
        long memberDelta,
        long postDelta) {
}
