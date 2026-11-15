package com.isles.manage.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 禁言时长（个人禁言 / 全员禁言共用）：60 秒 – 30 天。
 */
public record MuteRequest(
        @NotNull(message = "禁言时长不能为空")
        @Min(value = 60, message = "禁言时长最短 60 秒")
        @Max(value = 2_592_000, message = "禁言时长最长 30 天")
        Long durationSeconds) {
}
