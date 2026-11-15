package com.isles.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 平台内容治理接口模型（docs/11 §5.4）。
 */
public final class AdminModerationDtos {

    private AdminModerationDtos() {
    }

    public record ContentItem(
            String id,
            String targetType,
            String channelId,
            String authorId,
            String title,
            String contentPreview,
            String status,
            Instant createdAt) {
    }

    public record ContentPage(List<ContentItem> items, long total, int page, int size) {
    }

    /**
     * 下架/恢复必须填写原因：既用于通知作者，也进入审计。
     */
    public record TakedownRequest(
            @NotBlank(message = "下架原因不能为空")
            @Size(max = 200, message = "原因最长 200 个字符")
            String reason) {
    }

    public record ContentActionResponse(String id, String targetType, String status) {
    }
}
