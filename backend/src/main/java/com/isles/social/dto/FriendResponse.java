package com.isles.social.dto;

import java.time.Instant;

/**
 * 好友列表条目（{@code GET /api/v1/friends}）。
 *
 * @param userId    好友用户 ID
 * @param username  登录名
 * @param nickname  昵称
 * @param avatarUrl 头像
 * @param signature 签名
 * @param since     成为好友的时间（关系成立时间）
 */
public record FriendResponse(
        String userId,
        String username,
        String nickname,
        String avatarUrl,
        String signature,
        Instant since) {
}
