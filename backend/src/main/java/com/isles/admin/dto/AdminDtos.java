package com.isles.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 平台管理后台接口请求/响应模型（docs/03 §4.16、docs/05 待细化项）。
 */
public final class AdminDtos {

    private AdminDtos() {
    }

    public record AdminIdentityResponse(boolean admin, String userId, String username, String platformRole) {
    }

    public record AdminUserResponse(
            String id,
            String username,
            String nickname,
            String avatarUrl,
            String signature,
            String status,
            String platformRole,
            Instant createdAt) {
    }

    public record AdminUserPage(List<AdminUserResponse> items, long total, int page, int size) {
    }

    public record AdminChannelResponse(
            String id,
            Long channelNo,
            String name,
            String avatarUrl,
            String ownerId,
            long memberCount,
            long postCount,
            String type,
            String visibility,
            String status,
            Instant createdAt) {
    }

    public record AdminChannelPage(List<AdminChannelResponse> items, long total, int page, int size) {
    }

    /**
     * 平台数据概览：全站规模 + 当日新增/活跃近似 + AI 使用量 + 举报待办。
     */
    public record PlatformOverviewResponse(
            long userCount,
            long channelCount,
            long postCount,
            long commentCount,
            long todayNewUsers,
            long todayNewPosts,
            long todayActiveUsers,
            long aiConversationCount,
            long aiMessageCount,
            long pendingReportCount,
            boolean reportTableReady) {
    }

    /**
     * 举报条目：字段从 reports 表按常见命名做兼容映射（表结构由 V2 迁移定义，未就绪时 available=false）。
     */
    public record ReportResponse(
            String id,
            String reporterId,
            String targetType,
            String targetId,
            String reason,
            String detail,
            String status,
            Instant createdAt) {
    }

    public record ReportPage(List<ReportResponse> items, long total, boolean available, String dependency,
                             int page, int size) {
    }

    /**
     * 举报处置：action=DISMISS（驳回）/ RESOLVE（处置完成）。
     */
    public record HandleReportRequest(
            @NotBlank(message = "处置动作不能为空")
            @Pattern(regexp = "DISMISS|RESOLVE", message = "处置动作取值必须为 DISMISS 或 RESOLVE")
            String action,

            @Size(max = 500, message = "备注最长 500 个字符")
            String note) {
    }

    public record HandleReportResponse(String id, String status, String action, String note) {
    }
}
