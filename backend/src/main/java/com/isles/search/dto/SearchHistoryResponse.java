package com.isles.search.dto;

import java.time.Instant;

/**
 * 搜索历史条目（FR-SRCH-02，仅本人可见）。
 */
public record SearchHistoryResponse(String id, String keyword, Instant createdAt) {
}
