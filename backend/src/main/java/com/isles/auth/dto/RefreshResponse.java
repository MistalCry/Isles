package com.isles.auth.dto;

/**
 * 刷新 accessToken 响应（新的 refreshToken 通过 Set-Cookie 轮换）。
 */
public record RefreshResponse(String accessToken, String tokenType, long expiresIn) {
}
