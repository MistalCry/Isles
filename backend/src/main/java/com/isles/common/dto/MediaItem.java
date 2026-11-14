package com.isles.common.dto;

/**
 * 媒体引用（帖子 / 评论 / 上传响应共用，docs/05 §3.3）。
 */
public record MediaItem(
        String type,
        String url,
        String thumbUrl,
        Integer width,
        Integer height,
        Long durationMs) {
}
