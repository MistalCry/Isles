package com.isles.analytics.dto;

import java.time.LocalDate;

/**
 * 趋势图数据点（日期 + 数值）。
 */
public record TrendPointResponse(LocalDate date, long value) {
}
