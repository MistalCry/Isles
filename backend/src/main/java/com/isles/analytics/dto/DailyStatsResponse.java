package com.isles.analytics.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 单日统计指标（docs/03 §8.1 口径）。
 */
public record DailyStatsResponse(
        LocalDate statDate,
        long memberCount,
        long newMembers,
        long lostMembers,
        long visitUv,
        long postViews,
        long activeUsers,
        BigDecimal activeRate,
        long posts,
        long comments,
        long likes,
        long shares,
        long interactions,
        BigDecimal interactionRate) {
}
