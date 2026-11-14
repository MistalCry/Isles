package com.isles.channel.dto;

import java.time.Instant;

/**
 * 板块信息（GET /channels/{id}/boards）。
 */
public record BoardResponse(
        String id,
        String channelId,
        String name,
        String description,
        Integer sort,
        Long postCount,
        Instant createdAt) {
}
