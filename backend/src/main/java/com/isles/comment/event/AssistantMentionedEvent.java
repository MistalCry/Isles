package com.isles.comment.event;

/**
 * 有人在帖子/评论里 @ 了频道助手（事务内发布，消费方在提交后生成回复）。
 *
 * @param channelId 频道
 * @param postId    帖子
 * @param commentId 触发提及的评论（帖子正文提及则为 null）
 * @param authorId  发起提及的用户
 */
public record AssistantMentionedEvent(
        String channelId,
        String postId,
        String commentId,
        String authorId) {
}
