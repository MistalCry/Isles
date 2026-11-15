package com.isles.platform.penalty;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 用户处罚接口模型（docs/11 §5.4）。
 */
public final class PenaltyDtos {

    private PenaltyDtos() {
    }

    public record PenaltyView(
            String id,
            String userId,
            String type,
            String scope,
            String channelId,
            String reason,
            String reportId,
            String operatorId,
            Instant startAt,
            Instant endAt,
            String status,
            String revokedBy,
            Instant revokedAt,
            Instant createdAt) {
    }

    public record PenaltyListResponse(List<PenaltyView> items) {
    }

    /**
     * @param durationHours 处罚时长（小时）；null 表示永久，0 表示立即到期（仅用于测试）
     */
    public record CreatePenaltyRequest(
            @NotBlank(message = "处罚类型不能为空")
            @Pattern(regexp = "BAN|MUTE|WARN", message = "处罚类型取值必须为 BAN/MUTE/WARN")
            String type,

            @NotBlank(message = "处罚原因不能为空")
            @Size(max = 200, message = "处罚原因最长 200 个字符")
            String reason,

            @Min(value = 0, message = "处罚时长不能为负数")
            @Max(value = 87600, message = "处罚时长最长 10 年")
            Integer durationHours,

            String reportId) {
    }
}
