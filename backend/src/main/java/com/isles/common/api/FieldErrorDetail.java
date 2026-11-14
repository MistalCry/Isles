package com.isles.common.api;

/**
 * 字段级错误明细，用于 {@code VALIDATION_ERROR} 等需要指出具体字段的错误。
 */
public record FieldErrorDetail(String field, String message) {
}
