package com.isles.platform.announcement;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 公告接口模型（docs/11 §5.5）。
 */
public final class AnnouncementDtos {

    private AnnouncementDtos() {
    }

    public record AnnouncementView(
            String id,
            String title,
            String content,
            String level,
            String placement,
            String status,
            Instant startAt,
            Instant endAt,
            String createdBy,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record AnnouncementPage(List<AnnouncementView> items, long total, int page, int size) {
    }

    /**
     * 新建/编辑共用：{@code level} 与 {@code placement} 省略时取 INFO / BANNER。
     */
    public record SaveAnnouncementRequest(
            @NotBlank(message = "公告标题不能为空")
            @Size(max = 120, message = "标题最长 120 个字符")
            String title,

            @NotBlank(message = "公告内容不能为空")
            @Size(max = 1000, message = "内容最长 1000 个字符")
            String content,

            @Pattern(regexp = "INFO|WARNING|CRITICAL", message = "公告级别取值非法")
            String level,

            @Pattern(regexp = "BANNER|MODAL|INBOX", message = "展示位置取值非法")
            String placement,

            Instant startAt,

            Instant endAt) {
    }

    public record PublicAnnouncementResponse(List<AnnouncementView> items) {
    }
}
