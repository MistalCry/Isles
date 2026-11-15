package com.isles.analytics.dto;

import java.util.List;

/**
 * 成员分析：成员总数、身份组分布、新增成员趋势。
 */
public record MemberStatsResponse(
        long memberCount,
        List<RoleDistributionResponse> roleDistribution,
        List<TrendPointResponse> newMemberTrend) {
}
