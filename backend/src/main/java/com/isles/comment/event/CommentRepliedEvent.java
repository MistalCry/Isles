package com.isles.comment.event;

/**
 * 评论发表后的事件（用于「有人回复频道助手时自动接话」等旁路能力）。
 *
 * <p>只在事务内发布，消费方用 {@code @TransactionalEventListener(AFTER_COMMIT)} 接收，
 * 避免读不到尚未提交的评论。
 *
 * @param channelId      所属频道
 * @param postId         帖子
 * @param commentId      新评论 id
 * @param parentCommentId 父评论 id（为 null 表示顶级评论）
 * @param parentAuthorId 父评论作者（回复对象）
 * @param authorId       新评论作者
 */
public record CommentRepliedEvent(
        String channelId,
        String postId,
        String commentId,
        String parentCommentId,
        String parentAuthorId,
        String authorId) {
}
