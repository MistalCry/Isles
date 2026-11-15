package com.isles.search.dto;

import java.time.Instant;

/**
 * 搜索热词（GET /search/hot）。
 */
public record HotKeywordResponse(String keyword, long searchCount, Instant lastSearchedAt) {
}
