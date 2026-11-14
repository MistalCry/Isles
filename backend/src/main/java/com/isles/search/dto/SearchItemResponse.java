package com.isles.search.dto;

import java.time.Instant;

/**
 * 搜索结果条目：频道与帖子共用，以 {@code type} 区分（CHANNEL / POST）。
 *
 * <p>POST 结果额外携带作者与板块信息，便于前端复用帖子卡片；CHANNEL 结果的
 * {@code title/description} 即频道名/简介，{@code memberCount} 为成员数。
 */
public record SearchItemResponse(
        String type,
        String id,
        String title,
        String summary,
        String channelId,
        String channelName,
        String channelAvatarUrl,
        String avatarUrl,
        Long memberCount,
        Long likeCount,
        Long commentCount,
        String authorId,
        String authorUsername,
        String authorNickname,
        String authorAvatarUrl,
        String boardId,
        String boardName,
        Instant createdAt) {
}
