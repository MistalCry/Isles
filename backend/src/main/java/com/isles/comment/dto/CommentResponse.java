package com.isles.comment.dto;

import com.isles.common.dto.MediaItem;

import java.time.Instant;
import java.util.List;

/**
 * 评论响应：顶级评论带 replies（楼中楼），回复不带 replies。
 *
 * <p>AI 标识（docs/12 §6）：{@code isAiGenerated} 这条评论由 AI 代理生成，
 * {@code authorIsAgent} 作者是 AI 代理账号。作者信息已批量加载，不产生 N+1。
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
        List<CommentResponse> replies,
        boolean isAiGenerated,
        boolean authorIsAgent) {
}
