package com.isles.post.dto;

import java.time.Instant;

/**
 * 帖子卡片上的「最高赞评论」预览（docs/05 §2.3）。
 *
 * <p>字段与 {@code CommentResponse} 的子集对齐，前端 {@code toComment} 可直接复用；
 * 不返回 media/emoji/replies，卡片只展示文本与作者。
 */
public record TopCommentResponse(
        String id,
        String postId,
        String authorId,
        String authorNickname,
        String authorAvatarUrl,
        String content,
        long likeCount,
        boolean liked,
        Instant createdAt) {
}
