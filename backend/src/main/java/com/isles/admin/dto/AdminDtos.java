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

    /**
     * 平台身份：{@code permissions} 供前端按权限点显示/隐藏菜单（docs/11 §7.1）。
     */
    public record AdminIdentityResponse(boolean admin, String userId, String username, String platformRole,
                                        List<String> permissions) {
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
     * 举报条目（docs/11 §1.3：状态与频道侧统一为 PENDING/ACCEPTED/REJECTED）。
     */
    public record ReportResponse(
            String id,
            String reporterId,
            String targetType,
            String targetId,
            String reason,
            String detail,
            String status,
            Instant createdAt,
            String channelId,
            String handlerId,
            String handleAction,
            String handleNote,
            Instant handledAt) {
    }

    public record ReportPage(List<ReportResponse> items, long total, boolean available, String dependency,
                             int page, int size) {
    }

    /**
     * 举报处置：action=DISMISS（驳回，写 REJECTED）/ RESOLVE（通过，写 ACCEPTED）。
     *
     * <p>沿用既有契约的动作名（docs/05 §2.12），只修正落库状态值；{@code handleAction} 可选，
     * 用于在通过时同时下架内容或处罚作者。
     */
    public record HandleReportRequest(
            @NotBlank(message = "处置动作不能为空")
            @Pattern(regexp = "DISMISS|RESOLVE", message = "处置动作取值必须为 DISMISS 或 RESOLVE")
            String action,

            @Size(max = 500, message = "备注最长 500 个字符")
            String note,

            @Pattern(regexp = "NONE|DELETE_CONTENT|MUTE_AUTHOR|WARN", message = "处置方式取值非法")
            String handleAction) {
    }

    public record HandleReportResponse(String id, String status, String action, String note) {
    }
}
