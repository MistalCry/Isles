package com.isles.user.dto;

import java.time.Instant;

/**
 * 用户公开资料（docs/05 §2.1）。
 */
public record UserResponse(
        String id,
        String username,
        String nickname,
        String avatarUrl,
        String signature,
        String gender,
        String region,
        Instant createdAt) {
}
