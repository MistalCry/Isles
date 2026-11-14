package com.isles.post.dto;

import com.isles.common.dto.MediaItem;

import java.time.Instant;
import java.util.List;

/**
 * 帖子响应（列表卡片与详情共用，docs/05 §2.3）。
 */
public record PostResponse(
        String id,
        String channelId,
        String channelName,
        String channelAvatarUrl,
        String boardId,
        String boardName,
        String authorId,
        String authorNickname,
        String authorAvatarUrl,
        String title,
        String content,
        String contentFormat,
        String coverUrl,
        List<MediaItem> media,
        long viewCount,
        long likeCount,
        long commentCount,
        long shareCount,
        boolean liked,
        boolean pinned,
        boolean essence,
        Instant createdAt) {
}
