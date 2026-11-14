package com.isles.auth.dto;

import com.isles.user.dto.UserResponse;

/**
 * 登录成功响应：accessToken 由前端存内存；refreshToken 通过 HttpOnly Cookie 下发，不出现在响应体。
 */
public record LoginResponse(String accessToken, String tokenType, long expiresIn, UserResponse user) {
}
