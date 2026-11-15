package com.isles.admin.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.isles.admin.dto.AdminDtos;
import com.isles.admin.dto.AdminUserRow;
import com.isles.admin.mapper.AdminChannelMapper;
import com.isles.admin.mapper.AdminReportMapper;
import com.isles.admin.mapper.AdminStatsMapper;
import com.isles.admin.mapper.AdminUserMapper;
import com.isles.ai.service.AiConversationService;
import com.isles.channel.entity.Channel;
import com.isles.channel.mapper.ChannelMapper;
import com.isles.comment.entity.Comment;
import com.isles.comment.mapper.CommentMapper;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.security.CurrentUserProvider;
import com.isles.common.time.ClockTimes;
import com.isles.manage.entity.Report;
import com.isles.notification.entity.Notification;
import com.isles.notification.service.NotificationService;
import com.isles.platform.audit.PlatformAuditActions;
import com.isles.platform.audit.PlatformAuditService;
import com.isles.platform.penalty.PenaltyDtos;
import com.isles.platform.penalty.PenaltyService;
import com.isles.post.entity.Post;
import com.isles.post.mapper.PostMapper;
import com.isles.user.entity.User;
import com.isles.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台管理后台应用服务（docs/03 FR-PADM-02/03、docs/11 §5）。
 *
 * <p>职责：平台概览、用户/频道治理、举报处置。举报状态与频道侧统一为
 * {@code PENDING/ACCEPTED/REJECTED}，处置时写入处理人、处置方式与时间（修复 docs/11 §1.3 D1/D2）。
 */
@Service
@RequiredArgsConstructor
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MUTE_HOURS_FROM_REPORT = 24;

    private final AdminUserMapper adminUserMapper;
    private final AdminChannelMapper adminChannelMapper;
    private final AdminReportMapper adminReportMapper;
    private final AdminStatsMapper adminStatsMapper;
    private final UserMapper userMapper;
    private final ChannelMapper channelMapper;
    private final PostMapper postMapper;
    private final CommentMapper commentMapper;
    private final AiConversationService aiConversationService;
    private final CurrentUserProvider currentUserProvider;
    private final PenaltyService penaltyService;
    private final AdminModerationService moderationService;
    private final NotificationService notificationService;
    private final PlatformAuditService auditService;

    // ---------------------------------------------------------------------
    // 平台数据概览
    // ---------------------------------------------------------------------

    public AdminDtos.PlatformOverviewResponse overview() {
        LocalDateTime dayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay();
        long userCount = safeCount(userMapper.selectCount(Wrappers.<User>lambdaQuery().isNull(User::getDeletedAt)));
        long channelCount = safeCount(channelMapper.selectCount(Wrappers.<Channel>lambdaQuery()
                .isNull(Channel::getDeletedAt)
                .eq(Channel::getStatus, Channel.Status.ACTIVE.name())));
        long postCount = safeCount(postMapper.selectCount(Wrappers.<Post>lambdaQuery()
                .isNull(Post::getDeletedAt)
                .eq(Post::getStatus, Post.Status.PUBLISHED.name())));
        long commentCount = safeCount(commentMapper.selectCount(Wrappers.<Comment>lambdaQuery()
                .isNull(Comment::getDeletedAt)
                .eq(Comment::getStatus, Comment.Status.PUBLISHED.name())));
        long pendingReports = adminReportMapper.countPage(Report.Status.PENDING.name(), null, null);
        return new AdminDtos.PlatformOverviewResponse(
                userCount,
                channelCount,
                postCount,
                commentCount,
                adminStatsMapper.countUsersSince(dayStart),
                adminStatsMapper.countPostsSince(dayStart),
                adminStatsMapper.countActiveUsersSince(dayStart),
                aiConversationService.countConversations(),
                aiConversationService.countMessages(),
                pendingReports,
                true);
    }

    // ---------------------------------------------------------------------
    // 用户管理
    // ---------------------------------------------------------------------

    public AdminDtos.AdminUserPage listUsers(String keyword, String status, Integer page, Integer size) {
        int pageSize = normalizeSize(size);
        int pageNo = normalizePage(page);
        String pattern = likePattern(keyword);
        List<AdminUserRow> rows = adminUserMapper.selectPage(pattern, blankToNull(status), pageSize,
                (pageNo - 1) * pageSize);
        long total = adminUserMapper.countPage(pattern, blankToNull(status));
        List<AdminDtos.AdminUserResponse> items = new ArrayList<>(rows.size());
        for (AdminUserRow row : rows) {
            items.add(toUserResponse(row));
        }
        return new AdminDtos.AdminUserPage(items, total, pageNo, pageSize);
    }

    /**
     * 禁用用户：创建永久 BAN 处罚（同步 users.status），不能禁用自己。
     */
    @Transactional
    public AdminDtos.AdminUserResponse banUser(String userId) {
        String operator = currentUserProvider.requireUserId();
        if (userId.equals(operator)) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, "不能禁用当前登录的管理员账号");
        }
        AdminUserRow row = adminUserMapper.selectRowById(userId);
        if (row == null) {
            throw new AppException(ErrorCode.USER_NOT_FOUND);
        }
        if (penaltyService.findActive(userId, "BAN") == null) {
            penaltyService.create(userId,
                    new PenaltyDtos.CreatePenaltyRequest("BAN", "平台管理员封禁", null, null), operator);
        } else {
            adminUserMapper.updateStatus(userId, User.Status.BANNED.name());
        }
        auditService.record(operator, null, PlatformAuditActions.USER_BAN, "USER", userId,
                Map.of("username", row.getUsername() == null ? "" : row.getUsername()));
        row.setStatus(User.Status.BANNED.name());
        log.info("平台管理：用户封禁 userId={}", userId);
        return toUserResponse(row);
    }

    @Transactional
    public AdminDtos.AdminUserResponse unbanUser(String userId) {
        String operator = currentUserProvider.requireUserId();
        AdminUserRow row = adminUserMapper.selectRowById(userId);
        if (row == null) {
            throw new AppException(ErrorCode.USER_NOT_FOUND);
        }
        PenaltyDtos.PenaltyView activeBan = penaltyService.findActive(userId, "BAN");
        if (activeBan != null) {
            penaltyService.revoke(activeBan.id(), operator);
        } else {
            adminUserMapper.updateStatus(userId, User.Status.ACTIVE.name());
        }
        auditService.record(operator, null, PlatformAuditActions.USER_UNBAN, "USER", userId,
                Map.of("username", row.getUsername() == null ? "" : row.getUsername()));
        row.setStatus(User.Status.ACTIVE.name());
        log.info("平台管理：用户解封 userId={}", userId);
        return toUserResponse(row);
    }

    // ---------------------------------------------------------------------
    // 频道管理
    // ---------------------------------------------------------------------

    public AdminDtos.AdminChannelPage listChannels(String keyword, String status, Integer page, Integer size) {
        int pageSize = normalizeSize(size);
        int pageNo = normalizePage(page);
        String pattern = likePattern(keyword);
        List<Channel> rows = adminChannelMapper.selectPage(pattern, blankToNull(status), pageSize,
                (pageNo - 1) * pageSize);
        long total = adminChannelMapper.countPage(pattern, blankToNull(status));
        List<AdminDtos.AdminChannelResponse> items = new ArrayList<>(rows.size());
        for (Channel channel : rows) {
            items.add(toChannelResponse(channel));
        }
        return new AdminDtos.AdminChannelPage(items, total, pageNo, pageSize);
    }

    @Transactional
    public AdminDtos.AdminChannelResponse banChannel(String channelId) {
        return changeChannelStatus(channelId, Channel.Status.BANNED.name(), PlatformAuditActions.CHANNEL_BAN);
    }

    @Transactional
    public AdminDtos.AdminChannelResponse unbanChannel(String channelId) {
        return changeChannelStatus(channelId, Channel.Status.ACTIVE.name(), PlatformAuditActions.CHANNEL_UNBAN);
    }

    private AdminDtos.AdminChannelResponse changeChannelStatus(String channelId, String status, String action) {
        Channel channel = channelMapper.selectById(channelId);
        if (channel == null || channel.getDeletedAt() != null) {
            throw new AppException(ErrorCode.CHANNEL_NOT_FOUND);
        }
        adminChannelMapper.updateStatus(channelId, status);
        channel.setStatus(status);
        auditService.record(action, "CHANNEL", channelId, Map.of("status", status));
        log.info("平台管理：频道状态变更 channelId={} status={}", channelId, status);
        return toChannelResponse(channel);
    }

    // ---------------------------------------------------------------------
    // 举报处置
    // ---------------------------------------------------------------------

    public AdminDtos.ReportPage listReports(String status, String targetType, String keyword,
                                            Integer page, Integer size) {
        int pageSize = normalizeSize(size);
        int pageNo = normalizePage(page);
        // 兼容旧前端的 DISMISSED/RESOLVED 筛选值（V6 起状态统一为 ACCEPTED/REJECTED）
        String statusFilter = normalizeReportStatus(blankToNull(status));
        String targetTypeFilter = blankToNull(targetType);
        String pattern = likePattern(keyword);
        long total = adminReportMapper.countPage(statusFilter, targetTypeFilter, pattern);
        List<Report> rows = adminReportMapper.selectPage(statusFilter, targetTypeFilter, pattern, pageSize,
                (pageNo - 1) * pageSize);
        List<AdminDtos.ReportResponse> items = rows.stream().map(this::toReportResponse).toList();
        // available/dependency 为兼容旧前端保留：表已就绪，恒为 true/null
        return new AdminDtos.ReportPage(items, total, true, null, pageNo, pageSize);
    }

    /**
     * 举报处置：DISMISS → REJECTED；RESOLVE → ACCEPTED，并写入处理人/方式/时间。
     *
     * <p>通过时可附带处置动作：下架内容、禁言作者 24 小时或仅警告；处置结果通知举报人。
     */
    @Transactional
    public AdminDtos.HandleReportResponse handleReport(String reportId, AdminDtos.HandleReportRequest request) {
        String operator = currentUserProvider.requireUserId();
        Report report = adminReportMapper.selectById(reportId);
        if (report == null) {
            throw new AppException(ErrorCode.REPORT_NOT_FOUND);
        }
        if (!Report.Status.PENDING.name().equals(report.getStatus())) {
            throw new AppException(ErrorCode.REPORT_ALREADY_HANDLED);
        }

        boolean accepted = "RESOLVE".equalsIgnoreCase(request.action());
        String status = accepted ? Report.Status.ACCEPTED.name() : Report.Status.REJECTED.name();
        String handleAction = accepted && request.handleAction() != null ? request.handleAction() : "NONE";

        if (accepted) {
            applyHandleAction(report, handleAction, operator);
        }

        int updated = adminReportMapper.handle(reportId, status, operator, handleAction, request.note());
        if (updated == 0) {
            throw new AppException(ErrorCode.REPORT_ALREADY_HANDLED);
        }
        notifyReporter(report, accepted, request.note());
        auditService.record(operator, null, PlatformAuditActions.REPORT_HANDLE, "REPORT", reportId,
                reportDetail(report, status, handleAction, request.note()));
        log.info("平台管理：举报处置 reportId={} status={} action={}", reportId, status, handleAction);
        return new AdminDtos.HandleReportResponse(reportId, status, request.action(), request.note());
    }

    private void applyHandleAction(Report report, String handleAction, String operator) {
        String authorId = resolveAuthorId(report);
        switch (handleAction) {
            case "DELETE_CONTENT" -> {
                if (Report.TargetType.POST.name().equals(report.getTargetType())) {
                    moderationService.takedownPost(report.getTargetId(), "举报核实：" + report.getReason(), operator);
                } else if (Report.TargetType.COMMENT.name().equals(report.getTargetType())) {
                    moderationService.takedownComment(report.getTargetId(), "举报核实：" + report.getReason(), operator);
                }
            }
            case "MUTE_AUTHOR" -> {
                if (authorId != null) {
                    penaltyService.create(authorId, new PenaltyDtos.CreatePenaltyRequest("MUTE",
                            "举报核实：" + report.getReason(), MUTE_HOURS_FROM_REPORT, report.getId()), operator);
                }
            }
            case "WARN" -> {
                if (authorId != null) {
                    penaltyService.create(authorId, new PenaltyDtos.CreatePenaltyRequest("WARN",
                            "举报核实：" + report.getReason(), null, report.getId()), operator);
                }
            }
            default -> {
                // NONE：仅通过举报，不做内容与用户处置
            }
        }
    }

    private String resolveAuthorId(Report report) {
        if (Report.TargetType.POST.name().equals(report.getTargetType())) {
            Post post = postMapper.selectById(report.getTargetId());
            return post == null ? null : post.getAuthorId();
        }
        if (Report.TargetType.COMMENT.name().equals(report.getTargetType())) {
            Comment comment = commentMapper.selectById(report.getTargetId());
            return comment == null ? null : comment.getAuthorId();
        }
        return null;
    }

    private void notifyReporter(Report report, boolean accepted, String note) {
        String title = accepted ? "举报已处理" : "举报未通过";
        String content = note == null || note.isBlank() ? null : "处理说明：" + note;
        String postId = Report.TargetType.POST.name().equals(report.getTargetType()) ? report.getTargetId() : null;
        String commentId = Report.TargetType.COMMENT.name().equals(report.getTargetType()) ? report.getTargetId() : null;
        notificationService.notifySystem(report.getReporterId(), Notification.Type.REPORT_RESULT,
                report.getChannelId(), postId, commentId, title, content, null);
    }

    private Map<String, Object> reportDetail(Report report, String status, String handleAction, String note) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("targetType", report.getTargetType());
        detail.put("targetId", report.getTargetId());
        detail.put("status", status);
        detail.put("handleAction", handleAction);
        if (note != null && !note.isBlank()) {
            detail.put("note", note);
        }
        return detail;
    }

    // ---------------------------------------------------------------------
    // 内部方法
    // ---------------------------------------------------------------------

    private AdminDtos.ReportResponse toReportResponse(Report report) {
        return new AdminDtos.ReportResponse(
                report.getId(),
                report.getReporterId(),
                report.getTargetType(),
                report.getTargetId(),
                report.getReason(),
                report.getDescription(),
                report.getStatus(),
                ClockTimes.toInstant(report.getCreatedAt()),
                report.getChannelId(),
                report.getHandlerId(),
                report.getHandleAction(),
                report.getHandleNote(),
                ClockTimes.toInstant(report.getHandledAt()));
    }

    private AdminDtos.AdminUserResponse toUserResponse(AdminUserRow row) {
        return new AdminDtos.AdminUserResponse(row.getId(), row.getUsername(), row.getNickname(),
                row.getAvatarUrl(), row.getSignature(), row.getStatus(), row.getPlatformRole(),
                ClockTimes.toInstant(row.getCreatedAt()));
    }

    private AdminDtos.AdminChannelResponse toChannelResponse(Channel channel) {
        return new AdminDtos.AdminChannelResponse(
                channel.getId(),
                channel.getChannelNo(),
                channel.getName(),
                channel.getAvatarUrl(),
                channel.getOwnerId(),
                channel.getMemberCount() == null ? 0 : channel.getMemberCount(),
                channel.getPostCount() == null ? 0 : channel.getPostCount(),
                channel.getType(),
                channel.getVisibility(),
                channel.getStatus(),
                ClockTimes.toInstant(channel.getCreatedAt()));
    }

    private long safeCount(Long value) {
        return value == null ? 0L : value;
    }

    private int normalizeSize(Integer size) {
        return size == null || size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
    }

    private int normalizePage(Integer page) {
        return page == null || page <= 0 ? 1 : page;
    }

    private String likePattern(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String value = keyword.trim().replaceAll("[%_\\\\]", "");
        return value.isEmpty() ? null : "%" + value + "%";
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 举报状态兼容映射：旧前端仍可能按 {@code DISMISSED}/{@code RESOLVED} 筛选。
     */
    private String normalizeReportStatus(String status) {
        if (status == null) {
            return null;
        }
        return switch (status.toUpperCase()) {
            case "DISMISSED" -> Report.Status.REJECTED.name();
            case "RESOLVED" -> Report.Status.ACCEPTED.name();
            default -> status;
        };
    }
}
