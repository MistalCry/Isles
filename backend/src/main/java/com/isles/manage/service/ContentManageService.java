package com.isles.manage.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.isles.channel.audit.AuditLogService;
import com.isles.channel.audit.ChannelAuditLog;
import com.isles.channel.entity.Channel;
import com.isles.channel.mapper.BoardMapper;
import com.isles.channel.mapper.ChannelMapper;
import com.isles.channel.service.ChannelAccessService;
import com.isles.channel.service.ChannelPermissions;
import com.isles.comment.entity.Comment;
import com.isles.comment.mapper.CommentMapper;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.pagination.CursorUtils;
import com.isles.common.pagination.OffsetPage;
import com.isles.common.pagination.PageResult;
import com.isles.common.time.ClockTimes;
import com.isles.manage.dto.BatchPostRequest;
import com.isles.notification.entity.Notification;
import com.isles.notification.service.NotificationService;
import com.isles.post.dto.PostResponse;
import com.isles.post.entity.Post;
import com.isles.post.mapper.PostMapper;
import com.isles.post.service.PostService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内容管理（FR-MGR-05、FR-POST-06/07、FR-CMT-07）：
 * 管理列表筛选、置顶/精华/删除、批量操作、评论删除。
 *
 * <p>权限：{@code channel.content.manage} + 具体动作权限点；子板块管理员仅限所属板块。
 */
@Service
@RequiredArgsConstructor
public class ContentManageService {

    private static final Logger log = LoggerFactory.getLogger(ContentManageService.class);
    private static final int MAX_BATCH_SIZE = 50;

    private final PostMapper postMapper;
    private final CommentMapper commentMapper;
    private final ChannelMapper channelMapper;
    private final BoardMapper boardMapper;
    private final ChannelAccessService accessService;
    private final PostService postService;
    private final AuditLogService auditLogService;
    private final NotificationService notificationService;

    /**
     * 内容管理列表：关键词/发帖人/板块/状态/时间筛选，含已删除/隐藏内容。
     */
    public PageResult<PostResponse> manageList(String channelId, String keyword, String authorId, String boardId,
                                               String status, LocalDate from, LocalDate to, String cursor,
                                               Integer limit, String actorId) {
        ChannelAccessService.ChannelAccessContext context = accessService
                .requirePermission(channelId, actorId, ChannelPermissions.CONTENT_MANAGE);
        String boardFilter = boardId;
        if (context.role() != null && context.role().getScopeBoardId() != null) {
            if (boardId != null && !boardId.isBlank() && !boardId.equals(context.role().getScopeBoardId())) {
                throw new AppException(ErrorCode.FORBIDDEN, "子板块管理员只能查看所属板块内容");
            }
            boardFilter = context.role().getScopeBoardId();
        }
        String statusValue = status == null || status.isBlank() ? null : status;
        if (statusValue != null && !List.of(Post.Status.PUBLISHED.name(), Post.Status.DELETED.name(),
                Post.Status.HIDDEN.name()).contains(statusValue)) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "status 取值非法");
        }
        int pageSize = CursorUtils.normalizeLimit(limit);
        String cursorId = CursorUtils.validateIdCursor(cursor);
        String pattern = keyword == null || keyword.isBlank()
                ? null
                : "%" + escapeLike(keyword.trim()) + "%";
        List<Post> rows = postMapper.selectChannelManage(channelId, statusValue, pattern, authorId, boardFilter,
                from == null ? null : from.atStartOfDay(),
                to == null ? null : to.plusDays(1).atStartOfDay(),
                cursorId, pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<Post> page = hasMore ? rows.subList(0, pageSize) : rows;
        String nextCursor = hasMore && !page.isEmpty() ? page.get(page.size() - 1).getId() : null;
        return new PageResult<>(postService.toResponses(page, actorId), nextCursor);
    }

    /**
     * 内容管理列表 - 管理端偏移分页（docs/05 §1「管理列表用 page/size」）。
     *
     * <p>权限与筛选条件与 {@link #manageList} 完全一致，仅分页方式不同；返回 {@code items + total}。
     */
    public OffsetPage<PostResponse> manageListOffset(String channelId, String keyword, String authorId,
                                                     String boardId, String status, LocalDate from, LocalDate to,
                                                     int page, int size, String actorId) {
        ChannelAccessService.ChannelAccessContext context = accessService
                .requirePermission(channelId, actorId, ChannelPermissions.CONTENT_MANAGE);
        String boardFilter = boardId;
        if (context.role() != null && context.role().getScopeBoardId() != null) {
            if (boardId != null && !boardId.isBlank() && !boardId.equals(context.role().getScopeBoardId())) {
                throw new AppException(ErrorCode.FORBIDDEN, "子板块管理员只能查看所属板块内容");
            }
            boardFilter = context.role().getScopeBoardId();
        }
        String statusValue = status == null || status.isBlank() ? null : status;
        if (statusValue != null && !List.of(Post.Status.PUBLISHED.name(), Post.Status.DELETED.name(),
                Post.Status.HIDDEN.name()).contains(statusValue)) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "status 取值非法");
        }
        int pageNumber = Math.max(1, page);
        int pageSize = Math.min(Math.max(1, size), 100);
        String pattern = keyword == null || keyword.isBlank()
                ? null
                : "%" + escapeLike(keyword.trim()) + "%";
        LocalDateTime fromTime = from == null ? null : from.atStartOfDay();
        LocalDateTime toTime = to == null ? null : to.plusDays(1).atStartOfDay();
        long total = postMapper.countChannelManage(channelId, statusValue, pattern, authorId, boardFilter,
                fromTime, toTime);
        List<Post> rows = postMapper.selectChannelManageOffset(channelId, statusValue, pattern, authorId,
                boardFilter, fromTime, toTime, (pageNumber - 1) * pageSize, pageSize);
        return new OffsetPage<>(postService.toResponses(rows, actorId), total);
    }

    /**
     * 置顶/取消置顶。
     */
    @Transactional
    public PostResponse pin(String channelId, String postId, boolean pinned, String actorId) {
        Post post = requireChannelPost(channelId, postId);
        accessService.requirePostManagePermission(channelId, actorId, post.getBoardId(),
                pinned ? ChannelPermissions.POST_PIN : ChannelPermissions.POST_PIN);
        postMapper.updatePinned(postId, pinned, pinned ? ClockTimes.nowUtc() : null);
        post.setIsPinned(pinned);
        post.setPinnedAt(pinned ? ClockTimes.nowUtc() : null);
        auditLogService.record(channelId, actorId,
                pinned ? ChannelAuditLog.Action.POST_PIN : ChannelAuditLog.Action.POST_UNPIN, "POST", postId);
        return singleResponse(post, actorId);
    }

    /**
     * 加精/取消加精。
     */
    @Transactional
    public PostResponse essence(String channelId, String postId, boolean essence, String actorId) {
        Post post = requireChannelPost(channelId, postId);
        accessService.requirePostManagePermission(channelId, actorId, post.getBoardId(),
                ChannelPermissions.POST_ESSENCE);
        postMapper.updateEssence(postId, essence);
        post.setIsEssence(essence);
        auditLogService.record(channelId, actorId,
                essence ? ChannelAuditLog.Action.POST_ESSENCE : ChannelAuditLog.Action.POST_UNESSENCE, "POST", postId);
        return singleResponse(post, actorId);
    }

    /**
     * 删除帖子：作者本人或具备 post.delete 权限的管理者；软删除并更新计数。
     *
     * @param channelId 管理入口传入频道 ID；作者删除入口可传 null（从帖子反查频道）
     */
    @Transactional
    public void deletePost(String channelId, String postId, String actorId) {
        Post post = postMapper.selectById(postId);
        if (post == null || post.getDeletedAt() != null
                || Post.Status.DELETED.name().equals(post.getStatus())) {
            throw new AppException(ErrorCode.POST_NOT_FOUND);
        }
        if (channelId != null && !channelId.equals(post.getChannelId())) {
            throw new AppException(ErrorCode.POST_NOT_FOUND);
        }
        boolean author = actorId.equals(post.getAuthorId());
        if (!author) {
            accessService.requirePostManagePermission(post.getChannelId(), actorId, post.getBoardId(),
                    ChannelPermissions.POST_DELETE);
        }
        boolean wasPublished = Post.Status.PUBLISHED.name().equals(post.getStatus());
        postMapper.softDelete(postId);
        if (wasPublished) {
            channelMapper.addPostCount(post.getChannelId(), -1);
            boardMapper.addPostCount(post.getBoardId(), -1);
        }
        if (!author) {
            notificationService.notifySystem(post.getAuthorId(), Notification.Type.POST_REMOVED, post.getChannelId(),
                    post.getId(), null, "帖子已被删除", "你在频道中的帖子被管理员删除，如有疑问可联系频道管理员",
                    Map.of("postId", post.getId()));
        }
        auditLogService.record(post.getChannelId(), actorId, ChannelAuditLog.Action.POST_DELETE, "POST", postId,
                Map.of("authorId", post.getAuthorId(), "self", author));
        log.info("删除帖子 channelId={} postId={} actor={}", post.getChannelId(), postId, actorId);
    }

    /**
     * 批量操作：置顶/取消/精华/取消/删除；任一失败整体回滚。
     */
    @Transactional
    public int batch(String channelId, BatchPostRequest request, String actorId) {
        accessService.requirePermission(channelId, actorId, ChannelPermissions.CONTENT_MANAGE);
        if (request.postIds().size() > MAX_BATCH_SIZE) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "单次最多操作 50 条帖子");
        }
        String action = request.action();
        int affected = 0;
        for (String postId : request.postIds()) {
            Post post = requireChannelPost(channelId, postId);
            switch (action) {
                case "PIN" -> {
                    accessService.requirePostManagePermission(channelId, actorId, post.getBoardId(),
                            ChannelPermissions.POST_PIN);
                    postMapper.updatePinned(postId, true, ClockTimes.nowUtc());
                    affected++;
                }
                case "UNPIN" -> {
                    accessService.requirePostManagePermission(channelId, actorId, post.getBoardId(),
                            ChannelPermissions.POST_PIN);
                    postMapper.updatePinned(postId, false, null);
                    affected++;
                }
                case "ESSENCE" -> {
                    accessService.requirePostManagePermission(channelId, actorId, post.getBoardId(),
                            ChannelPermissions.POST_ESSENCE);
                    postMapper.updateEssence(postId, true);
                    affected++;
                }
                case "UNESSENCE" -> {
                    accessService.requirePostManagePermission(channelId, actorId, post.getBoardId(),
                            ChannelPermissions.POST_ESSENCE);
                    postMapper.updateEssence(postId, false);
                    affected++;
                }
                case "DELETE" -> {
                    deletePost(channelId, postId, actorId);
                    affected++;
                }
                default -> throw new AppException(ErrorCode.VALIDATION_ERROR, "批量操作类型非法");
            }
        }
        auditLogService.record(channelId, actorId, ChannelAuditLog.Action.POST_BATCH, "POST", null,
                Map.of("action", action, "postIds", request.postIds(), "affected", affected));
        log.info("批量内容操作 channelId={} action={} affected={}", channelId, action, affected);
        return affected;
    }

    /**
     * 删除评论：作者本人或具备 comment.delete 权限的管理者。
     */
    @Transactional
    public void deleteComment(String commentId, String actorId) {
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null || comment.getDeletedAt() != null
                || Comment.Status.DELETED.name().equals(comment.getStatus())) {
            throw new AppException(ErrorCode.COMMENT_NOT_FOUND);
        }
        Post post = postMapper.selectById(comment.getPostId());
        if (post == null) {
            throw new AppException(ErrorCode.POST_NOT_FOUND);
        }
        boolean author = actorId.equals(comment.getAuthorId());
        if (!author) {
            accessService.requirePostManagePermission(post.getChannelId(), actorId, post.getBoardId(),
                    ChannelPermissions.COMMENT_DELETE);
        }
        boolean wasPublished = Comment.Status.PUBLISHED.name().equals(comment.getStatus());
        comment.setStatus(Comment.Status.DELETED.name());
        comment.setDeletedAt(ClockTimes.nowUtc());
        commentMapper.updateById(comment);
        if (wasPublished) {
            postMapper.addCommentCount(post.getId(), -1);
        }
        if (!author) {
            notificationService.notifySystem(comment.getAuthorId(), Notification.Type.POST_REMOVED,
                    post.getChannelId(), post.getId(), comment.getId(), "评论已被删除",
                    "你的评论被管理员删除，如有疑问可联系频道管理员",
                    Map.of("postId", post.getId(), "commentId", comment.getId()));
        }
        auditLogService.record(post.getChannelId(), actorId, ChannelAuditLog.Action.COMMENT_DELETE, "COMMENT",
                commentId, Map.of("authorId", comment.getAuthorId(), "self", author));
    }

    private Post requireChannelPost(String channelId, String postId) {
        Post post = postMapper.selectById(postId);
        if (post == null || !channelId.equals(post.getChannelId())) {
            throw new AppException(ErrorCode.POST_NOT_FOUND);
        }
        return post;
    }

    private PostResponse singleResponse(Post post, String viewerId) {
        return postService.toResponses(List.of(post), viewerId).get(0);
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
