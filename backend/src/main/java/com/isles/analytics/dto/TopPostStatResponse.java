package com.isles.analytics.dto;

import java.time.Instant;

/**
 * 内容分析 - Top 帖子。
 */
public record TopPostStatResponse(
        String postId,
        String title,
        String contentPreview,
        long viewCount,
        long likeCount,
        long commentCount,
        long shareCount,
        Instant createdAt) {
}
