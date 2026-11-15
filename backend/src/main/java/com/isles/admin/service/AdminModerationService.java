package com.isles.admin.service;

import com.isles.admin.dto.AdminContentRow;
import com.isles.admin.dto.AdminModerationDtos;
import com.isles.admin.mapper.AdminContentMapper;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.pagination.OffsetPage;
import com.isles.common.time.ClockTimes;
import com.isles.notification.entity.Notification;
import com.isles.notification.service.NotificationService;
import com.isles.platform.audit.PlatformAuditActions;
import com.isles.platform.audit.PlatformAuditService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台内容治理（docs/11 §5.4、§6.2）：全站检索帖子/评论，下架与恢复。
 *
 * <p>下架是「软处理」：只改 {@code status=HIDDEN}，不删除数据，可随时恢复；作者会收到系统通知，
 * 操作写平台审计。频道内的内容管理（{@code ContentManageService}）保持不变，二者互不替代。
 */
@Service
@RequiredArgsConstructor
public class AdminModerationService {

    private static final Logger log = LoggerFactory.getLogger(AdminModerationService.class);
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final AdminContentMapper contentMapper;
    private final NotificationService notificationService;
    private final PlatformAuditService auditService;

    public AdminModerationDtos.ContentPage listPosts(String keyword, String channelId, String authorId,
                                                     String status, Integer page, Integer size) {
        int pageNo = normalizePage(page);
        int pageSize = normalizeSize(size);
        String pattern = likePattern(keyword);
        String statusFilter = blankToNull(status);
        long total = contentMapper.countPosts(pattern, blankToNull(channelId), blankToNull(authorId), statusFilter);
        List<AdminContentRow> rows = contentMapper.selectPosts(pattern, blankToNull(channelId),
                blankToNull(authorId), statusFilter, pageSize, (pageNo - 1) * pageSize);
        return new AdminModerationDtos.ContentPage(toItems(rows), total, pageNo, pageSize);
    }

    public AdminModerationDtos.ContentPage listComments(String keyword, String channelId, String authorId,
                                                        String status, Integer page, Integer size) {
        int pageNo = normalizePage(page);
        int pageSize = normalizeSize(size);
        String pattern = likePattern(keyword);
        String statusFilter = blankToNull(status);
        long total = contentMapper.countComments(pattern, blankToNull(channelId), blankToNull(authorId), statusFilter);
        List<AdminContentRow> rows = contentMapper.selectComments(pattern, blankToNull(channelId),
                blankToNull(authorId), statusFilter, pageSize, (pageNo - 1) * pageSize);
        return new AdminModerationDtos.ContentPage(toItems(rows), total, pageNo, pageSize);
    }

    /**
     * 下架帖子：{@code PUBLISHED → HIDDEN}；重复下架幂等（不重复发通知）。
     */
    @Transactional
    public AdminModerationDtos.ContentActionResponse takedownPost(String postId, String reason, String operatorId) {
        AdminContentRow row = contentMapper.selectPost(postId);
        if (row == null) {
            throw new AppException(ErrorCode.POST_NOT_FOUND);
        }
        boolean alreadyHidden = "HIDDEN".equalsIgnoreCase(row.getStatus());
        contentMapper.updatePostStatus(postId, "HIDDEN");
        if (!alreadyHidden) {
            notifyAuthor(row, "帖子已被平台下架", reason, postId, null);
            audit(operatorId, PlatformAuditActions.CONTENT_TAKEDOWN, "POST", postId, row, reason);
        }
        return new AdminModerationDtos.ContentActionResponse(postId, "POST", "HIDDEN");
    }

    @Transactional
    public AdminModerationDtos.ContentActionResponse restorePost(String postId, String operatorId) {
        AdminContentRow row = contentMapper.selectPost(postId);
        if (row == null) {
            throw new AppException(ErrorCode.POST_NOT_FOUND);
        }
        boolean alreadyPublished = "PUBLISHED".equalsIgnoreCase(row.getStatus());
        contentMapper.updatePostStatus(postId, "PUBLISHED");
        if (!alreadyPublished) {
            notifyAuthor(row, "帖子已恢复", "平台复核后已恢复展示", postId, null);
            audit(operatorId, PlatformAuditActions.CONTENT_RESTORE, "POST", postId, row, null);
        }
        return new AdminModerationDtos.ContentActionResponse(postId, "POST", "PUBLISHED");
    }

    @Transactional
    public AdminModerationDtos.ContentActionResponse takedownComment(String commentId, String reason,
                                                                     String operatorId) {
        AdminContentRow row = contentMapper.selectComment(commentId);
        if (row == null) {
            throw new AppException(ErrorCode.COMMENT_NOT_FOUND);
        }
        boolean alreadyHidden = "HIDDEN".equalsIgnoreCase(row.getStatus());
        contentMapper.updateCommentStatus(commentId, "HIDDEN");
        if (!alreadyHidden) {
            notifyAuthor(row, "评论已被平台下架", reason, null, commentId);
            audit(operatorId, PlatformAuditActions.CONTENT_TAKEDOWN, "COMMENT", commentId, row, reason);
        }
        return new AdminModerationDtos.ContentActionResponse(commentId, "COMMENT", "HIDDEN");
    }

    @Transactional
    public AdminModerationDtos.ContentActionResponse restoreComment(String commentId, String operatorId) {
        AdminContentRow row = contentMapper.selectComment(commentId);
        if (row == null) {
            throw new AppException(ErrorCode.COMMENT_NOT_FOUND);
        }
        boolean alreadyPublished = "PUBLISHED".equalsIgnoreCase(row.getStatus());
        contentMapper.updateCommentStatus(commentId, "PUBLISHED");
        if (!alreadyPublished) {
            notifyAuthor(row, "评论已恢复", "平台复核后已恢复展示", null, commentId);
            audit(operatorId, PlatformAuditActions.CONTENT_RESTORE, "COMMENT", commentId, row, null);
        }
        return new AdminModerationDtos.ContentActionResponse(commentId, "COMMENT", "PUBLISHED");
    }

    private void notifyAuthor(AdminContentRow row, String title, String reason, String postId, String commentId) {
        if (row.getAuthorId() == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("channelId", row.getChannelId());
        if (postId != null) {
            payload.put("postId", postId);
        }
        if (commentId != null) {
            payload.put("commentId", commentId);
        }
        String content = reason == null || reason.isBlank() ? null : "原因：" + reason;
        notificationService.notifySystem(row.getAuthorId(), Notification.Type.POST_REMOVED, row.getChannelId(),
                postId, commentId, title, content, payload);
    }

    private void audit(String operatorId, String action, String targetType, String targetId,
                       AdminContentRow row, String reason) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("previousStatus", row.getStatus());
        detail.put("channelId", row.getChannelId());
        detail.put("authorId", row.getAuthorId());
        if (reason != null && !reason.isBlank()) {
            detail.put("reason", reason);
        }
        auditService.record(operatorId, null, action, targetType, targetId, detail);
    }

    private List<AdminModerationDtos.ContentItem> toItems(List<AdminContentRow> rows) {
        return rows.stream()
                .map(row -> new AdminModerationDtos.ContentItem(row.getId(), row.getTargetType(), row.getChannelId(),
                        row.getAuthorId(), row.getTitle(), row.getContentPreview(), row.getStatus(),
                        ClockTimes.toInstant(row.getCreatedAt())))
                .toList();
    }

    private int normalizePage(Integer page) {
        return page == null || page <= 0 ? 1 : page;
    }

    private int normalizeSize(Integer size) {
        return size == null || size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
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
