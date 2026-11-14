package com.isles.search.dto;

import java.util.List;

/**
 * 搜索联想（FR-SRCH-03 M1 基础版）：已加入频道前缀匹配 + 搜索日志中的高频词。
 */
public record SearchSuggestResponse(List<SearchItemResponse> channels, List<String> keywords) {
}
