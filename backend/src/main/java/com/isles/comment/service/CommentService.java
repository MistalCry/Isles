package com.isles.comment.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.isles.channel.service.ChannelAccessService;
import com.isles.channel.service.ChannelPermissions;
import com.isles.comment.dto.CommentResponse;
import com.isles.comment.dto.CreateCommentRequest;
import com.isles.comment.entity.Comment;
import com.isles.comment.mapper.CommentMapper;
import com.isles.common.dto.MediaItem;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.id.UlidGenerator;
import com.isles.common.json.JsonSupport;
import com.isles.common.media.MediaPayloads;
import com.isles.common.pagination.CursorUtils;
import com.isles.common.pagination.PageResult;
import com.isles.common.time.ClockTimes;
import com.isles.interaction.service.InteractionService;
import com.isles.notification.entity.Notification;
import com.isles.notification.service.NotificationService;
import com.isles.post.entity.Post;
import com.isles.post.mapper.PostMapper;
import com.isles.user.entity.User;
import com.isles.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 评论服务：列表（点赞/最新排序，楼中楼）、发表、回复、点赞（点赞委托 InteractionService）。
 *
 * <p>评论媒体/表情以 JSON 列存储，读写通过 {@link JsonSupport}；权限经
 * {@link ChannelAccessService#requireInteractionPermission}（成员 + 禁言 + comment.create）。
 */
@Service
@RequiredArgsConstructor
public class CommentService {

    private static final String SORT_LIKED = "likes";
    private static final String SORT_LATEST = "latest";
    private static final int MAX_CONTENT_LENGTH = 2000;
    private static final int NOTIFICATION_SNIPPET_LENGTH = 100;

    private final CommentMapper commentMapper;
    private final PostMapper postMapper;
    private final UserMapper userMapper;
    private final ChannelAccessService accessService;
    private final InteractionService interactionService;
    private final NotificationService notificationService;
    private final JsonSupport jsonSupport;

    /**
     * 评论列表：默认按点赞数排序，支持最新排序，游标分页；回复一次性批量加载避免 N+1。
     */
    public PageResult<CommentResponse> listComments(String postId, String sort, String cursor, Integer limit,
                                                    String viewerId) {
        Post post = requireVisiblePost(postId, viewerId);
        int pageSize = CursorUtils.normalizeLimit(limit);
        String sortValue = sort == null || sort.isBlank() ? SORT_LIKED : sort;
        List<Comment> rows;
        String nextCursor = null;
        if (SORT_LATEST.equals(sortValue)) {
            String cursorId = CursorUtils.validateIdCursor(cursor);
            rows = commentMapper.selectTopLatest(post.getId(), cursorId, pageSize + 1);
        } else if (SORT_LIKED.equals(sortValue)) {
            CursorUtils.HotCursor hotCursor = CursorUtils.parseHotCursor(cursor);
            rows = commentMapper.selectTopLiked(post.getId(),
                    hotCursor == null ? null : hotCursor.score(),
                    hotCursor == null ? null : hotCursor.id(),
                    pageSize + 1);
        } else {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "sort 取值必须为 likes 或 latest");
        }
        boolean hasMore = rows.size() > pageSize;
        List<Comment> page = hasMore ? rows.subList(0, pageSize) : rows;
        if (!page.isEmpty() && hasMore) {
            Comment last = page.get(page.size() - 1);
            nextCursor = SORT_LIKED.equals(sortValue)
                    ? CursorUtils.formatHotCursor(last.getLikeCount(), last.getId())
                    : last.getId();
        }
        return new PageResult<>(toResponses(page, viewerId), nextCursor);
    }

    /**
     * 发表评论或回复。
     */
    @Transactional
    public CommentResponse createComment(String postId, CreateCommentRequest request, String userId) {
        Post post = requireVisiblePost(postId, userId);
        accessService.requireInteractionPermission(post.getChannelId(), userId, ChannelPermissions.COMMENT_CREATE);

        String content = request.content() == null ? "" : request.content().trim();
        List<MediaItem> media = MediaPayloads.normalize(request.media());
        List<String> emoji = normalizeEmoji(request.emoji());
        if (content.isEmpty() && media.isEmpty() && emoji.isEmpty()) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "评论内容、媒体或表情至少填写一项");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "评论最长 2000 个字符");
        }

        Comment parent = null;
        if (request.parentId() != null && !request.parentId().isBlank()) {
            parent = commentMapper.selectById(request.parentId());
            if (parent == null || parent.getDeletedAt() != null
                    || !Comment.Status.PUBLISHED.name().equals(parent.getStatus())) {
                throw new AppException(ErrorCode.COMMENT_NOT_FOUND);
            }
            if (!postId.equals(parent.getPostId())) {
                throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, "父评论不属于该帖子");
            }
            if (parent.getParentId() != null) {
                throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, "回复必须指向顶级评论（仅支持两级）");
            }
        }
        String replyToUserId = normalizeReplyToUserId(request.replyToUserId());

        LocalDateTime now = ClockTimes.nowUtc();
        Comment comment = new Comment();
        comment.setId(UlidGenerator.generate());
        comment.setPostId(postId);
        comment.setAuthorId(userId);
        comment.setParentId(parent == null ? null : parent.getId());
        comment.setReplyToUserId(replyToUserId);
        comment.setContent(content);
        comment.setMedia(media.isEmpty() ? null : jsonSupport.toJson(media));
        comment.setEmoji(emoji.isEmpty() ? null : jsonSupport.toJson(emoji));
        comment.setLikeCount(0L);
        comment.setStatus(Comment.Status.PUBLISHED.name());
        comment.setCreatedAt(now);
        commentMapper.insert(comment);
        postMapper.addCommentCount(postId, 1);

        notifyComment(post, parent, comment, userId);
        User author = userMapper.selectById(userId);
        return buildResponse(comment, author, null, false, null, List.of());
    }

    private void notifyComment(Post post, Comment parent, Comment comment, String actorId) {
        String snippet = comment.getContent() == null || comment.getContent().isBlank()
                ? "[媒体/表情评论]"
                : truncate(comment.getContent(), NOTIFICATION_SNIPPET_LENGTH);
        if (parent == null) {
            notificationService.notifyUser(post.getAuthorId(), Notification.Type.POST_COMMENTED,
                    post.getChannelId(), post.getId(), comment.getId(), actorId, "收到评论", snippet);
            return;
        }
        String targetUserId = comment.getReplyToUserId() == null ? parent.getAuthorId() : comment.getReplyToUserId();
        notificationService.notifyUser(targetUserId, Notification.Type.COMMENT_REPLIED,
                post.getChannelId(), post.getId(), comment.getId(), actorId, "收到回复", snippet);
    }

    private List<CommentResponse> toResponses(List<Comment> topComments, String viewerId) {
        if (topComments == null || topComments.isEmpty()) {
            return List.of();
        }
        List<String> topIds = topComments.stream().map(Comment::getId).toList();
        List<Comment> replies = commentMapper.selectList(Wrappers.<Comment>lambdaQuery()
                .in(Comment::getParentId, topIds)
                .eq(Comment::getStatus, Comment.Status.PUBLISHED.name())
                .orderByAsc(Comment::getCreatedAt));
        Map<String, List<Comment>> repliesByParent = replies.stream()
                .collect(Collectors.groupingBy(Comment::getParentId));

        Set<String> userIds = new java.util.HashSet<>();
        topComments.forEach(comment -> {
            userIds.add(comment.getAuthorId());
            if (comment.getReplyToUserId() != null) {
                userIds.add(comment.getReplyToUserId());
            }
        });
        replies.forEach(comment -> {
            userIds.add(comment.getAuthorId());
            if (comment.getReplyToUserId() != null) {
                userIds.add(comment.getReplyToUserId());
            }
        });
        userIds.remove(null);
        Map<String, User> users = userIds.isEmpty()
                ? Map.of()
                : userMapper.selectBatchIds(userIds).stream()
                        .collect(Collectors.toMap(User::getId, Function.identity()));

        List<String> allCommentIds = new ArrayList<>(topIds);
        replies.forEach(reply -> allCommentIds.add(reply.getId()));
        Set<String> likedIds = interactionService.likedCommentIds(allCommentIds, viewerId);

        List<CommentResponse> responses = new ArrayList<>(topComments.size());
        for (Comment top : topComments) {
            List<CommentResponse> replyResponses = repliesByParent.getOrDefault(top.getId(), List.of()).stream()
                    .map(reply -> buildResponse(reply, users.get(reply.getAuthorId()),
                            reply.getReplyToUserId() == null ? null : users.get(reply.getReplyToUserId()),
                            likedIds.contains(reply.getId()), top.getId(), List.of()))
                    .toList();
            responses.add(buildResponse(top, users.get(top.getAuthorId()),
                    top.getReplyToUserId() == null ? null : users.get(top.getReplyToUserId()),
                    likedIds.contains(top.getId()), null, replyResponses));
        }
        return responses;
    }

    private CommentResponse buildResponse(Comment comment, User author, User replyToUser, boolean liked,
                                          String parentId, List<CommentResponse> replies) {
        return new CommentResponse(
                comment.getId(),
                comment.getPostId(),
                comment.getParentId() == null ? parentId : comment.getParentId(),
                comment.getAuthorId(),
                author == null ? null : author.getNickname(),
                author == null ? null : author.getAvatarUrl(),
                comment.getReplyToUserId(),
                replyToUser == null ? null : replyToUser.getNickname(),
                comment.getContent(),
                parseMedia(comment.getMedia()),
                parseEmoji(comment.getEmoji()),
                comment.getLikeCount(),
                liked,
                ClockTimes.toInstant(comment.getCreatedAt()),
                replies);
    }

    private Post requireVisiblePost(String postId, String viewerId) {
        Post post = postMapper.selectById(postId);
        if (post == null || post.getDeletedAt() != null || !Post.Status.PUBLISHED.name().equals(post.getStatus())) {
            throw new AppException(ErrorCode.POST_NOT_FOUND);
        }
        try {
            accessService.requireVisibleChannel(post.getChannelId(), viewerId);
        } catch (AppException ex) {
            throw new AppException(ErrorCode.POST_NOT_FOUND);
        }
        return post;
    }

    private String normalizeReplyToUserId(String replyToUserId) {
        if (replyToUserId == null || replyToUserId.isBlank()) {
            return null;
        }
        String value = replyToUserId.trim();
        if (userMapper.selectById(value) == null) {
            throw new AppException(ErrorCode.USER_NOT_FOUND);
        }
        return value;
    }

    private List<String> normalizeEmoji(List<String> emoji) {
        if (emoji == null || emoji.isEmpty()) {
            return List.of();
        }
        List<String> values = emoji.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
        if (values.size() > 20) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "表情最多 20 个");
        }
        return values;
    }

    private List<MediaItem> parseMedia(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        MediaItem[] items = jsonSupport.fromJson(json, MediaItem[].class);
        return List.of(items);
    }

    private List<String> parseEmoji(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        String[] values = jsonSupport.fromJson(json, String[].class);
        return List.of(values);
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
