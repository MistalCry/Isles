package com.isles.common.api;

/**
 * 统一错误响应信封：{@code { "error": { ... } }}。
 */
public record ErrorResponse(ApiError error) {
}
