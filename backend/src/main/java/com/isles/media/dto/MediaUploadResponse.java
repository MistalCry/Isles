package com.isles.media.dto;

/**
 * 上传响应（docs/05 §2.6）：返回站内 URL、缩略图与尺寸信息。
 */
public record MediaUploadResponse(
        String id,
        String url,
        String thumbUrl,
        String mediaType,
        Integer width,
        Integer height,
        Long durationMs) {
}
