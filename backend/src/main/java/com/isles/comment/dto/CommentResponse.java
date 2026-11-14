package com.isles.comment.dto;

import com.isles.common.dto.MediaItem;

import java.time.Instant;
import java.util.List;

/**
 * 评论响应：顶级评论带 replies（楼中楼），回复不带 replies。
 */
public record CommentResponse(
        String id,
        String postId,
        String parentId,
        String authorId,
        String authorNickname,
        String authorAvatarUrl,
        String replyToUserId,
        String replyToNickname,
        String content,
        List<MediaItem> media,
        List<String> emoji,
        long likeCount,
        boolean liked,
        Instant createdAt,
        List<CommentResponse> replies) {
}
