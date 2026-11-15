package com.isles.analytics.dto;

/**
 * 成员分析 - 身份组分布。
 */
public record RoleDistributionResponse(String roleId, String roleName, long memberCount) {
}
