package com.isles.user.dto;

import java.time.Instant;

/**
 * 用户公开资料（docs/05 §2.1）。
 *
 * <p>{@code isAgent=true} 表示这是 AI 代理账号，前台必须展示「AI 代理」标识（docs/12 §6，不可关闭）。
 */
public record UserResponse(
        String id,
        String username,
        String nickname,
        String avatarUrl,
        String signature,
        String gender,
        String region,
        Instant createdAt,
        boolean isAgent) {
}
