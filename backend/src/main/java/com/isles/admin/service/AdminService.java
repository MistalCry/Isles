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
import java.util.List;
import java.util.Map;

/**
 * 平台管理后台应用服务（docs/03 FR-PADM-02/03）：平台概览、用户/频道管理、举报处置。
 *
 * <p>举报依赖 WP1 的 {@code reports} 表：表未就绪时查询返回 available=false，处置返回明确业务错误，
 * 不阻塞其余后台能力（见任务报告「已知依赖」）。
 */
@Service
@RequiredArgsConstructor
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    private static final String REPORT_TABLE_DEPENDENCY = "reports（由 V2 迁移创建，等待 backend-dev 交付）";
    private static final int DEFAULT_PAGE_SIZE = 20;

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
        long pendingReports = 0L;
        boolean reportReady = reportTableReady();
        if (reportReady) {
            try {
                pendingReports = adminReportMapper.countPage("PENDING");
            } catch (RuntimeException ex) {
                log.warn("举报待办统计失败（reports 表结构可能不同）");
            }
        }
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
                reportReady);
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
            items.add(new AdminDtos.AdminUserResponse(row.getId(), row.getUsername(), row.getNickname(),
                    row.getAvatarUrl(), row.getSignature(), row.getStatus(), row.getPlatformRole(),
                    ClockTimes.toInstant(row.getCreatedAt())));
        }
        return new AdminDtos.AdminUserPage(items, total, pageNo, pageSize);
    }

    /**
     * 禁用用户（BANNED）：登录与公开资料查询都会拒绝；不能禁用自己。
     */
    @Transactional
    public AdminDtos.AdminUserResponse banUser(String userId) {
        String operator = currentUserProvider.requireUserId();
        if (userId.equals(operator)) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, "不能禁用当前登录的管理员账号");
        }
        return changeUserStatus(userId, User.Status.BANNED.name());
    }

    @Transactional
    public AdminDtos.AdminUserResponse unbanUser(String userId) {
        return changeUserStatus(userId, User.Status.ACTIVE.name());
    }

    private AdminDtos.AdminUserResponse changeUserStatus(String userId, String status) {
        AdminUserRow row = adminUserMapper.selectRowById(userId);
        if (row == null) {
            throw new AppException(ErrorCode.USER_NOT_FOUND);
        }
        adminUserMapper.updateStatus(userId, status);
        row.setStatus(status);
        log.info("平台管理：用户状态变更 userId={} status={}", userId, status);
        return new AdminDtos.AdminUserResponse(row.getId(), row.getUsername(), row.getNickname(),
                row.getAvatarUrl(), row.getSignature(), row.getStatus(), row.getPlatformRole(),
                ClockTimes.toInstant(row.getCreatedAt()));
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
        return changeChannelStatus(channelId, Channel.Status.BANNED.name());
    }

    @Transactional
    public AdminDtos.AdminChannelResponse unbanChannel(String channelId) {
        return changeChannelStatus(channelId, Channel.Status.ACTIVE.name());
    }

    private AdminDtos.AdminChannelResponse changeChannelStatus(String channelId, String status) {
        Channel channel = channelMapper.selectById(channelId);
        if (channel == null || channel.getDeletedAt() != null) {
            throw new AppException(ErrorCode.CHANNEL_NOT_FOUND);
        }
        adminChannelMapper.updateStatus(channelId, status);
        channel.setStatus(status);
        log.info("平台管理：频道状态变更 channelId={} status={}", channelId, status);
        return toChannelResponse(channel);
    }

    // ---------------------------------------------------------------------
    // 举报处置（依赖 reports 表）
    // ---------------------------------------------------------------------

    public AdminDtos.ReportPage listReports(String status, Integer page, Integer size) {
        int pageSize = normalizeSize(size);
        int pageNo = normalizePage(page);
        if (!reportTableReady()) {
            return new AdminDtos.ReportPage(List.of(), 0, false, REPORT_TABLE_DEPENDENCY, pageNo, pageSize);
        }
        String statusValue = blankToNull(status);
        List<Map<String, Object>> rows;
        try {
            rows = adminReportMapper.selectPage(statusValue, pageSize, (pageNo - 1) * pageSize);
        } catch (RuntimeException ex) {
            log.warn("举报列表查询失败（reports 表结构可能不同）");
            return new AdminDtos.ReportPage(List.of(), 0, false, REPORT_TABLE_DEPENDENCY, pageNo, pageSize);
        }
        long total = adminReportMapper.countPage(statusValue);
        List<AdminDtos.ReportResponse> items = rows.stream().map(this::toReportResponse).toList();
        return new AdminDtos.ReportPage(items, total, true, null, pageNo, pageSize);
    }

    /**
     * 举报处置：DISMISS → DISMISSED；RESOLVE → RESOLVED。表未就绪时返回明确业务错误。
     */
    @Transactional
    public AdminDtos.HandleReportResponse handleReport(String reportId, String action, String note) {
        if (!reportTableReady()) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "举报数据表尚未就绪（等待 V2 迁移），暂时无法处置举报");
        }
        String status = "DISMISS".equalsIgnoreCase(action) ? "DISMISSED" : "RESOLVED";
        try {
            adminReportMapper.updateStatus(reportId, status);
        } catch (RuntimeException ex) {
            log.warn("举报处置失败 reportId={}", reportId);
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, "举报处置失败：举报表结构不匹配");
        }
        log.info("平台管理：举报处置 reportId={} action={} status={}", reportId, action, status);
        return new AdminDtos.HandleReportResponse(reportId, status, action, note);
    }

    // ---------------------------------------------------------------------
    // 内部方法
    // ---------------------------------------------------------------------

    private boolean reportTableReady() {
        try {
            return adminReportMapper.countReportsTable() > 0;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private AdminDtos.ReportResponse toReportResponse(Map<String, Object> row) {
        return new AdminDtos.ReportResponse(
                stringValue(row, "id"),
                stringValue(row, "reporterId", "reporter_id", "userId", "user_id"),
                stringValue(row, "targetType", "target_type", "type"),
                stringValue(row, "targetId", "target_id"),
                stringValue(row, "reason"),
                stringValue(row, "detail", "description", "content"),
                stringValue(row, "status"),
                instantValue(row, "createdAt", "created_at"));
    }

    private String stringValue(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            Object value = row.get(key);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private java.time.Instant instantValue(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            Object value = row.get(key);
            if (value instanceof java.sql.Timestamp timestamp) {
                return timestamp.toInstant();
            }
            if (value instanceof LocalDateTime dateTime) {
                return ClockTimes.toInstant(dateTime);
            }
            if (value instanceof java.time.Instant instant) {
                return instant;
            }
            if (value != null) {
                return null;
            }
        }
        return null;
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
        return size == null || size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, 100);
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
}
