package com.isles.interaction.dto;

/**
 * 点赞/取消点赞结果（帖子与评论通用）。
 */
public record LikeStateResponse(boolean liked, long likeCount) {
}
