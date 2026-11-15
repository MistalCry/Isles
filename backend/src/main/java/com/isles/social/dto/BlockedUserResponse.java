package com.isles.social.dto;

import java.time.Instant;

/**
 * 黑名单条目（{@code GET /api/v1/friends/blocks}）。
 *
 * @param userId    被拉黑用户 ID
 * @param username  登录名
 * @param nickname  昵称
 * @param avatarUrl 头像
 * @param blockedAt 拉黑时间
 */
public record BlockedUserResponse(
        String userId,
        String username,
        String nickname,
        String avatarUrl,
        Instant blockedAt) {
}
