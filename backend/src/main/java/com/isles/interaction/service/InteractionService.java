package com.isles.interaction.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.isles.channel.service.ChannelAccessService;
import com.isles.comment.entity.Comment;
import com.isles.comment.mapper.CommentLikeMapper;
import com.isles.comment.mapper.CommentMapper;
import com.isles.comment.entity.CommentLike;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.id.UlidGenerator;
import com.isles.common.time.ClockTimes;
import com.isles.interaction.dto.LikeStateResponse;
import com.isles.notification.entity.Notification;
import com.isles.notification.service.NotificationService;
import com.isles.post.entity.Post;
import com.isles.post.entity.PostLike;
import com.isles.post.mapper.PostLikeMapper;
import com.isles.post.mapper.PostMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 互动服务（docs/03 §5.2 InteractionService）：点赞/取消点赞、浏览计数、分享计数。
 *
 * <p>计数用原子 SQL 更新；点赞防重复由唯一约束 + 预检查保证。
 * 私密频道内容统一走 {@link ChannelAccessService} 可见性校验，防止越权互动。
 */
@Service
@RequiredArgsConstructor
public class InteractionService {

    private final PostMapper postMapper;
    private final PostLikeMapper postLikeMapper;
    private final CommentMapper commentMapper;
    private final CommentLikeMapper commentLikeMapper;
    private final ChannelAccessService accessService;
    private final NotificationService notificationService;

    /**
     * 帖子点赞（需登录；对公开频道非成员也开放，符合 FR-AUTH-04"互动需登录"）。
     */
    @Transactional
    public LikeStateResponse likePost(String postId, String userId) {
        Post post = requireVisiblePost(postId, userId);
        Long existing = postLikeMapper.selectCount(Wrappers.<PostLike>lambdaQuery()
                .eq(PostLike::getPostId, postId)
                .eq(PostLike::getUserId, userId));
        if (existing != null && existing > 0) {
            throw new AppException(ErrorCode.ALREADY_LIKED);
        }
        PostLike like = new PostLike();
        like.setId(UlidGenerator.generate());
        like.setPostId(postId);
        like.setUserId(userId);
        like.setCreatedAt(ClockTimes.nowUtc());
        postLikeMapper.insert(like);
        postMapper.addLikeCount(postId, 1);
        notificationService.notifyUser(post.getAuthorId(), Notification.Type.POST_LIKED, post.getChannelId(),
                post.getId(), null, userId, "收到点赞", "你的帖子收到一个赞");
        return new LikeStateResponse(true, post.getLikeCount() + 1);
    }

    /**
     * 取消帖子点赞（幂等）。
     */
    @Transactional
    public LikeStateResponse unlikePost(String postId, String userId) {
        Post post = requireVisiblePost(postId, userId);
        int deleted = postLikeMapper.delete(Wrappers.<PostLike>lambdaQuery()
                .eq(PostLike::getPostId, postId)
                .eq(PostLike::getUserId, userId));
        if (deleted > 0) {
            postMapper.addLikeCount(postId, -1);
            return new LikeStateResponse(false, Math.max(post.getLikeCount() - 1, 0));
        }
        return new LikeStateResponse(false, post.getLikeCount());
    }

    /**
     * 分享计数（复制链接）。
     */
    @Transactional
    public long sharePost(String postId, String userId) {
        Post post = requireVisiblePost(postId, userId);
        postMapper.addShareCount(postId);
        return post.getShareCount() + 1;
    }

    /**
     * 浏览量 +1（详情接口调用；防刷策略 P2）。
     */
    public void incrementViewCount(String postId) {
        postMapper.addViewCount(postId);
    }

    /**
     * 评论点赞。
     */
    @Transactional
    public LikeStateResponse likeComment(String commentId, String userId) {
        Comment comment = requireVisibleComment(commentId, userId);
        Long existing = commentLikeMapper.selectCount(Wrappers.<CommentLike>lambdaQuery()
                .eq(CommentLike::getCommentId, commentId)
                .eq(CommentLike::getUserId, userId));
        if (existing != null && existing > 0) {
            throw new AppException(ErrorCode.ALREADY_LIKED);
        }
        CommentLike like = new CommentLike();
        like.setId(UlidGenerator.generate());
        like.setCommentId(commentId);
        like.setUserId(userId);
        like.setCreatedAt(ClockTimes.nowUtc());
        commentLikeMapper.insert(like);
        commentMapper.addLikeCount(commentId, 1);
        return new LikeStateResponse(true, comment.getLikeCount() + 1);
    }

    /**
     * 取消评论点赞（幂等）。
     */
    @Transactional
    public LikeStateResponse unlikeComment(String commentId, String userId) {
        Comment comment = requireVisibleComment(commentId, userId);
        int deleted = commentLikeMapper.delete(Wrappers.<CommentLike>lambdaQuery()
                .eq(CommentLike::getCommentId, commentId)
                .eq(CommentLike::getUserId, userId));
        if (deleted > 0) {
            commentMapper.addLikeCount(commentId, -1);
            return new LikeStateResponse(false, Math.max(comment.getLikeCount() - 1, 0));
        }
        return new LikeStateResponse(false, comment.getLikeCount());
    }

    /**
     * 批量查询当前用户点赞过的帖子 ID（列表页避免逐条查询）。
     */
    public Set<String> likedPostIds(List<String> postIds, String viewerId) {
        if (viewerId == null || postIds == null || postIds.isEmpty()) {
            return Set.of();
        }
        return postLikeMapper.selectList(Wrappers.<PostLike>lambdaQuery()
                        .in(PostLike::getPostId, postIds)
                        .eq(PostLike::getUserId, viewerId))
                .stream()
                .map(PostLike::getPostId)
                .collect(Collectors.toSet());
    }

    /**
     * 批量查询当前用户点赞过的评论 ID。
     */
    public Set<String> likedCommentIds(List<String> commentIds, String viewerId) {
        if (viewerId == null || commentIds == null || commentIds.isEmpty()) {
            return Set.of();
        }
        return commentLikeMapper.selectList(Wrappers.<CommentLike>lambdaQuery()
                        .in(CommentLike::getCommentId, commentIds)
                        .eq(CommentLike::getUserId, viewerId))
                .stream()
                .map(CommentLike::getCommentId)
                .collect(Collectors.toSet());
    }

    /**
     * 加载可见的已发布帖子：不存在/已删除/隐藏 -> 404；频道不可见 -> 404（不泄露存在性）。
     */
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

    private Comment requireVisibleComment(String commentId, String viewerId) {
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null || comment.getDeletedAt() != null
                || !Comment.Status.PUBLISHED.name().equals(comment.getStatus())) {
            throw new AppException(ErrorCode.COMMENT_NOT_FOUND);
        }
        Post post = postMapper.selectById(comment.getPostId());
        if (post == null || post.getDeletedAt() != null || !Post.Status.PUBLISHED.name().equals(post.getStatus())) {
            throw new AppException(ErrorCode.COMMENT_NOT_FOUND);
        }
        try {
            accessService.requireVisibleChannel(post.getChannelId(), viewerId);
        } catch (AppException ex) {
            throw new AppException(ErrorCode.COMMENT_NOT_FOUND);
        }
        return comment;
    }
}
