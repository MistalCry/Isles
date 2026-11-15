package com.isles.social.dto;

/**
 * 好友相关接口中的用户简要信息（嵌套在申请条目 fromUser/toUser 中）。
 *
 * <p>{@code id} 与 {@code userId} 同时返回，兼容前端 `UserSummary.id` 与后端惯用的 {@code userId}。
 */
public record FriendUserBrief(
        String id,
        String userId,
        String username,
        String nickname,
        String avatarUrl) {
}
