package com.isles.apps.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 频道活动接口模型（V10 / docs/05 §2.14）。
 */
public final class ActivityDtos {

    private ActivityDtos() {
    }

    public record ActivityView(
            String id,
            String channelId,
            String title,
            String description,
            Instant startAt,
            Instant endAt,
            String location,
            Integer capacity,
            String status,
            long goingCount,
            boolean signedUp,
            Instant createdAt) {
    }

    public record ActivityPage(List<ActivityView> items, long total, int page, int size) {
    }

    public record SaveActivityRequest(
            @NotBlank(message = "活动标题不能为空")
            @Size(max = 100, message = "活动标题最长 100 个字符")
            String title,

            @Size(max = 1000, message = "活动描述最长 1000 个字符")
            String description,

            @NotNull(message = "开始时间不能为空")
            Instant startAt,

            Instant endAt,

            @Size(max = 200, message = "活动地点最长 200 个字符")
            String location,

            @Min(value = 1, message = "报名上限至少 1")
            @Max(value = 10000, message = "报名上限最大 10000")
            Integer capacity) {
    }

    public record SignupView(
            String userId,
            String nickname,
            String avatarUrl,
            String status,
            Instant createdAt) {
    }

    public record SignupList(List<SignupView> items, long goingCount) {
    }
}
