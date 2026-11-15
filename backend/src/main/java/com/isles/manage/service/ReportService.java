package com.isles.manage.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.isles.channel.audit.AuditLogService;
import com.isles.channel.audit.ChannelAuditLog;
import com.isles.channel.entity.Channel;
import com.isles.channel.mapper.ChannelMapper;
import com.isles.channel.mapper.ReportMapper;
import com.isles.channel.service.ChannelAccessService;
import com.isles.channel.service.ChannelPermissions;
import com.isles.comment.entity.Comment;
import com.isles.comment.mapper.CommentMapper;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.id.UlidGenerator;
import com.isles.common.pagination.CursorUtils;
import com.isles.common.pagination.OffsetPage;
import com.isles.common.pagination.PageResult;
import com.isles.common.security.HtmlSanitizer;
import com.isles.common.time.ClockTimes;
import com.isles.manage.dto.HandleReportRequest;
import com.isles.manage.dto.MuteRequest;
import com.isles.manage.dto.ReportCreateRequest;
import com.isles.manage.dto.ReportResponse;
import com.isles.manage.entity.Report;
import com.isles.notification.entity.Notification;
import com.isles.notification.service.NotificationService;
import com.isles.post.entity.Post;
import com.isles.post.mapper.PostMapper;
import com.isles.user.entity.User;
import com.isles.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 举报与处置（FR-ITR-03、FR-PADM-02 频道侧）：
 * 用户举报帖子/评论 → 频道管理员列表 → 通过/驳回，可删除内容或禁言作者，并通知举报人与作者。
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);
    private static final int PREVIEW_LENGTH = 80;
    private static final long DEFAULT_MUTE_SECONDS = 86_400L;

    private final ReportMapper reportMapper;
    private final PostMapper postMapper;
    private final CommentMapper commentMapper;
    private final ChannelMapper channelMapper;
    private final UserMapper userMapper;
    private final ChannelAccessService accessService;
    private final ContentManageService contentManageService;
    private final MemberManageService memberManageService;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;

    /**
     * 举报目标摘要（频道、作者、预览）。
     */
    private record TargetInfo(String channelId, String authorId, String preview) {
    }

    /**
     * 创建举报：需登录且目标内容对当前用户可见；同一用户对同一内容只允许一条待处理举报。
     */
    @Transactional
    public ReportResponse create(ReportCreateRequest request, String userId) {
        Report.TargetType targetType = parseTargetType(request.targetType());
        TargetInfo target = loadTarget(targetType, request.targetId());
        accessService.requireVisibleChannel(target.channelId(), userId);
        Long pending = reportMapper.selectCount(Wrappers.<Report>lambdaQuery()
                .eq(Report::getReporterId, userId)
                .eq(Report::getTargetType, targetType.name())
                .eq(Report::getTargetId, request.targetId())
                .eq(Report::getStatus, Report.Status.PENDING.name()));
        if (pending != null && pending > 0) {
            throw new AppException(ErrorCode.REPORT_ALREADY_EXISTS);
        }
        LocalDateTime now = ClockTimes.nowUtc();
        Report report = new Report();
        report.setId(UlidGenerator.generate());
        report.setReporterId(userId);
        report.setChannelId(target.channelId());
        report.setTargetType(targetType.name());
        report.setTargetId(request.targetId());
        report.setReason(request.reason());
        report.setDescription(request.description());
        report.setStatus(Report.Status.PENDING.name());
        report.setCreatedAt(now);
        reportMapper.insert(report);
        auditLogService.record(target.channelId(), userId, ChannelAuditLog.Action.REPORT_CREATE,
                targetType.name(), request.targetId(), Map.of("reason", request.reason()));
        log.info("创建举报 channelId={} targetType={} targetId={} reporter={}", target.channelId(),
                targetType, request.targetId(), userId);
        return toResponses(List.of(report)).get(0);
    }

    /**
     * 我的举报记录。
     */
    public PageResult<ReportResponse> listMine(String userId, String cursor, Integer limit) {
        return list(null, userId, null, null, cursor, limit);
    }

    /**
     * 频道举报列表（内容管理权限）。
     */
    public PageResult<ReportResponse> listChannel(String channelId, String status, String targetType, String cursor,
                                                  Integer limit, String actorId) {
        accessService.requirePermission(channelId, actorId, ChannelPermissions.CONTENT_MANAGE);
        String statusValue = validateStatus(status);
        String targetValue = targetType == null || targetType.isBlank() ? null : targetType;
        if (targetValue != null && !List.of(Report.TargetType.POST.name(), Report.TargetType.COMMENT.name())
                .contains(targetValue)) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "targetType 取值非法");
        }
        return list(channelId, null, statusValue, targetValue, cursor, limit);
    }

    /**
     * 频道举报列表 - 管理端偏移分页（docs/05 §1）：返回 {@code items + total}。
     */
    public OffsetPage<ReportResponse> listChannelOffset(String channelId, String status, String targetType,
                                                        int page, int size, String actorId) {
        accessService.requirePermission(channelId, actorId, ChannelPermissions.CONTENT_MANAGE);
        String statusValue = validateStatus(status);
        String targetValue = targetType == null || targetType.isBlank() ? null : targetType;
        if (targetValue != null && !List.of(Report.TargetType.POST.name(), Report.TargetType.COMMENT.name())
                .contains(targetValue)) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "targetType 取值非法");
        }
        int pageNumber = Math.max(1, page);
        int pageSize = Math.min(Math.max(1, size), 100);
        long total = reportMapper.countPage(null, channelId, statusValue, targetValue);
        List<Report> rows = reportMapper.selectPageOffset(null, channelId, statusValue, targetValue,
                (pageNumber - 1) * pageSize, pageSize);
        return new OffsetPage<>(toResponses(rows), total);
    }

    /**
     * 处置举报：通过（可删除内容/禁言作者）或驳回；处理结果通知举报人。
     */
    @Transactional
    public ReportResponse handle(String channelId, String reportId, HandleReportRequest request, String actorId) {
        accessService.requirePermission(channelId, actorId, ChannelPermissions.CONTENT_MANAGE);
        Report report = reportMapper.selectById(reportId);
        if (report == null || !channelId.equals(report.getChannelId())) {
            throw new AppException(ErrorCode.REPORT_NOT_FOUND);
        }
        if (!Report.Status.PENDING.name().equals(report.getStatus())) {
            throw new AppException(ErrorCode.REPORT_ALREADY_HANDLED);
        }
        Report.Status status = parseStatus(request.status());
        String actionValue = request.action() == null || request.action().isBlank()
                ? Report.HandleAction.NONE.name() : request.action();
        Report.HandleAction action = parseAction(actionValue);
        if (status == Report.Status.REJECTED && action != Report.HandleAction.NONE) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "驳回举报时不能同时执行处置动作");
        }
        TargetInfo target = loadTarget(parseTargetType(report.getTargetType()), report.getTargetId());
        if (status == Report.Status.ACCEPTED) {
            applyAction(report, target, action, request, actorId);
        }
        LocalDateTime now = ClockTimes.nowUtc();
        report.setStatus(status.name());
        report.setHandlerId(actorId);
        report.setHandleAction(action.name());
        report.setHandleNote(request.note());
        report.setHandledAt(now);
        reportMapper.updateById(report);

        String resultText = status == Report.Status.ACCEPTED ? "已通过并处理" : "已驳回";
        notificationService.notifySystem(report.getReporterId(), Notification.Type.REPORT_RESULT, channelId,
                report.getTargetType().equals(Report.TargetType.POST.name()) ? report.getTargetId() : null,
                report.getTargetType().equals(Report.TargetType.COMMENT.name()) ? report.getTargetId() : null,
                "举报处理结果", "你提交的举报" + resultText,
                Map.of("reportId", reportId, "status", status.name(), "action", action.name()));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("status", status.name());
        detail.put("action", action.name());
        detail.put("note", request.note() == null ? "" : request.note());
        auditLogService.record(channelId, actorId, ChannelAuditLog.Action.REPORT_HANDLE, "REPORT", reportId, detail);
        log.info("处置举报 channelId={} reportId={} status={} action={}", channelId, reportId, status, action);
        return toResponses(List.of(report)).get(0);
    }

    private void applyAction(Report report, TargetInfo target, Report.HandleAction action,
                             HandleReportRequest request, String actorId) {
        switch (action) {
            case DELETE_CONTENT -> {
                try {
                    if (Report.TargetType.POST.name().equals(report.getTargetType())) {
                        contentManageService.deletePost(report.getChannelId(), report.getTargetId(), actorId);
                    } else {
                        contentManageService.deleteComment(report.getTargetId(), actorId);
                    }
                } catch (AppException ex) {
                    // 内容已被删除时视为处置完成
                    if (ex.getErrorCode() != ErrorCode.POST_NOT_FOUND
                            && ex.getErrorCode() != ErrorCode.COMMENT_NOT_FOUND) {
                        throw ex;
                    }
                }
            }
            case MUTE_AUTHOR -> {
                long duration = request.muteDurationSeconds() == null
                        ? DEFAULT_MUTE_SECONDS : request.muteDurationSeconds();
                memberManageService.mute(report.getChannelId(), target.authorId(),
                        new MuteRequest(duration), actorId);
            }
            case NONE -> {
                // 仅记录通过，不执行处置
            }
            default -> throw new AppException(ErrorCode.VALIDATION_ERROR, "处置动作非法");
        }
    }

    private PageResult<ReportResponse> list(String channelId, String reporterId, String status, String targetType,
                                            String cursor, Integer limit) {
        int pageSize = CursorUtils.normalizeLimit(limit);
        String cursorId = CursorUtils.validateIdCursor(cursor);
        List<Report> rows = reportMapper.selectPage(reporterId, channelId, status, targetType, cursorId, pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<Report> page = hasMore ? rows.subList(0, pageSize) : rows;
        List<ReportResponse> items = toResponses(page);
        String nextCursor = hasMore && !items.isEmpty() ? items.get(items.size() - 1).id() : null;
        return new PageResult<>(items, nextCursor);
    }

    /**
     * 批量组装举报响应：用户/频道/目标内容/目标作者均批量加载，避免 N+1。
     */
    private List<ReportResponse> toResponses(List<Report> reports) {
        if (reports == null || reports.isEmpty()) {
            return List.of();
        }
        List<String> postIds = reports.stream()
                .filter(report -> Report.TargetType.POST.name().equals(report.getTargetType()))
                .map(Report::getTargetId)
                .distinct()
                .toList();
        List<String> commentIds = reports.stream()
                .filter(report -> Report.TargetType.COMMENT.name().equals(report.getTargetType()))
                .map(Report::getTargetId)
                .distinct()
                .toList();
        Map<String, Post> posts = postIds.isEmpty() ? Map.of()
                : postMapper.selectBatchIds(postIds).stream()
                        .collect(Collectors.toMap(Post::getId, Function.identity()));
        Map<String, Comment> comments = commentIds.isEmpty() ? Map.of()
                : commentMapper.selectBatchIds(commentIds).stream()
                        .collect(Collectors.toMap(Comment::getId, Function.identity()));

        Map<String, String> targetAuthorIds = new HashMap<>();
        posts.forEach((id, post) -> targetAuthorIds.put(id, post.getAuthorId()));
        comments.forEach((id, comment) -> targetAuthorIds.put(id, comment.getAuthorId()));

        java.util.Set<String> userIds = new java.util.HashSet<>();
        reports.forEach(report -> {
            userIds.add(report.getReporterId());
            if (report.getHandlerId() != null) {
                userIds.add(report.getHandlerId());
            }
            String authorId = targetAuthorIds.get(report.getTargetId());
            if (authorId != null) {
                userIds.add(authorId);
            }
        });
        userIds.remove(null);
        Map<String, User> users = userIds.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(userIds).stream()
                        .collect(Collectors.toMap(User::getId, Function.identity()));

        java.util.Set<String> channelIds = reports.stream().map(Report::getChannelId)
                .collect(Collectors.toSet());
        Map<String, Channel> channels = channelIds.isEmpty() ? Map.of()
                : channelMapper.selectBatchIds(channelIds).stream()
                        .collect(Collectors.toMap(Channel::getId, Function.identity()));

        return reports.stream().map(report -> {
            String authorId = targetAuthorIds.get(report.getTargetId());
            User author = authorId == null ? null : users.get(authorId);
            User reporter = users.get(report.getReporterId());
            User handler = report.getHandlerId() == null ? null : users.get(report.getHandlerId());
            Channel channel = channels.get(report.getChannelId());
            return new ReportResponse(
                    report.getId(),
                    report.getReporterId(),
                    reporter == null ? null : reporter.getNickname(),
                    report.getChannelId(),
                    channel == null ? null : channel.getName(),
                    report.getTargetType(),
                    report.getTargetId(),
                    previewOf(report, posts, comments),
                    authorId,
                    author == null ? null : author.getNickname(),
                    report.getReason(),
                    report.getDescription(),
                    report.getStatus(),
                    report.getHandleAction(),
                    report.getHandleNote(),
                    report.getHandlerId(),
                    handler == null ? null : handler.getNickname(),
                    ClockTimes.toInstant(report.getHandledAt()),
                    ClockTimes.toInstant(report.getCreatedAt()));
        }).toList();
    }

    private String previewOf(Report report, Map<String, Post> posts, Map<String, Comment> comments) {
        if (Report.TargetType.POST.name().equals(report.getTargetType())) {
            Post post = posts.get(report.getTargetId());
            if (post == null) {
                return null;
            }
            String text = post.getTitle() != null && !post.getTitle().isBlank()
                    ? post.getTitle()
                    : HtmlSanitizer.toPlainText(post.getContent());
            return truncate(text);
        }
        Comment comment = comments.get(report.getTargetId());
        return comment == null ? null : truncate(comment.getContent());
    }

    private TargetInfo loadTarget(Report.TargetType targetType, String targetId) {
        if (targetType == Report.TargetType.POST) {
            Post post = postMapper.selectById(targetId);
            if (post == null || post.getDeletedAt() != null
                    || !Post.Status.PUBLISHED.name().equals(post.getStatus())) {
                throw new AppException(ErrorCode.REPORT_TARGET_INVALID);
            }
            String text = post.getTitle() != null && !post.getTitle().isBlank()
                    ? post.getTitle() : HtmlSanitizer.toPlainText(post.getContent());
            return new TargetInfo(post.getChannelId(), post.getAuthorId(), truncate(text));
        }
        Comment comment = commentMapper.selectById(targetId);
        if (comment == null || comment.getDeletedAt() != null
                || !Comment.Status.PUBLISHED.name().equals(comment.getStatus())) {
            throw new AppException(ErrorCode.REPORT_TARGET_INVALID);
        }
        Post post = postMapper.selectById(comment.getPostId());
        if (post == null) {
            throw new AppException(ErrorCode.REPORT_TARGET_INVALID);
        }
        return new TargetInfo(post.getChannelId(), comment.getAuthorId(), truncate(comment.getContent()));
    }

    private Report.TargetType parseTargetType(String value) {
        try {
            return Report.TargetType.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "举报目标类型非法");
        }
    }

    private Report.Status parseStatus(String value) {
        try {
            return Report.Status.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "处置结果非法");
        }
    }

    private Report.HandleAction parseAction(String value) {
        try {
            return Report.HandleAction.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "处置动作非法");
        }
    }

    private String validateStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        if (!List.of(Report.Status.PENDING.name(), Report.Status.ACCEPTED.name(), Report.Status.REJECTED.name())
                .contains(status)) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "status 取值非法");
        }
        return status;
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= PREVIEW_LENGTH ? value : value.substring(0, PREVIEW_LENGTH);
    }
}
