package com.isles.comment.service;

import com.isles.channel.entity.Channel;
import com.isles.channel.entity.ChannelMember;
import com.isles.channel.entity.Role;
import com.isles.channel.audit.AuditLogService;
import com.isles.channel.service.ChannelAccessService;
import com.isles.channel.service.ChannelPermissions;
import com.isles.comment.dto.CommentResponse;
import com.isles.comment.dto.CreateCommentRequest;
import com.isles.comment.entity.Comment;
import com.isles.comment.mapper.CommentMapper;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.json.JsonSupport;
import com.isles.interaction.service.InteractionService;
import com.isles.notification.entity.Notification;
import com.isles.notification.service.NotificationService;
import com.isles.post.entity.Post;
import com.isles.post.mapper.PostMapper;
import com.isles.user.entity.User;
import com.isles.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;

import org.springframework.context.ApplicationEventPublisher;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评论创建单元测试：顶级评论、回复校验、禁言拦截与通知。
 */
class CommentServiceTest {

    private CommentMapper commentMapper;
    private PostMapper postMapper;
    private UserMapper userMapper;
    private ChannelAccessService accessService;
    private InteractionService interactionService;
    private NotificationService notificationService;
    private CommentService commentService;
    private AuditLogService auditLogService;

    @BeforeEach
    void setUp() {
        commentMapper = mock(CommentMapper.class);
        postMapper = mock(PostMapper.class);
        userMapper = mock(UserMapper.class);
        accessService = mock(ChannelAccessService.class);
        interactionService = mock(InteractionService.class);
        notificationService = mock(NotificationService.class);
        auditLogService = mock(AuditLogService.class);
        commentService = new CommentService(commentMapper, postMapper, userMapper, accessService,
                interactionService, notificationService, new JsonSupport(), auditLogService, mock(ApplicationEventPublisher.class));
    }

    @Test
    void should_create_top_level_comment_and_notify_post_author() {
        when(postMapper.selectById("post-1")).thenReturn(post());
        when(accessService.requireVisibleChannel("ch-1", "user-2")).thenReturn(channel());
        when(accessService.requireInteractionPermission("ch-1", "user-2", ChannelPermissions.COMMENT_CREATE))
                .thenReturn(context());
        when(commentMapper.insert(any(Comment.class))).thenReturn(1);
        when(postMapper.addCommentCount("post-1", 1)).thenReturn(1);
        when(userMapper.selectById("user-2")).thenReturn(commenter());

        CommentResponse response = commentService.createComment("post-1",
                new CreateCommentRequest("第一条评论", null, null, null, null), "user-2");

        assertThat(response.content()).isEqualTo("第一条评论");
        assertThat(response.parentId()).isNull();
        assertThat(response.authorNickname()).isEqualTo("评论者");
        assertThat(response.liked()).isFalse();
        verify(commentMapper).insert(any(Comment.class));
        verify(postMapper).addCommentCount("post-1", 1);
        verify(notificationService).notifyUser(anyString(), eq(Notification.Type.POST_COMMENTED), eq("ch-1"),
                eq("post-1"), anyString(), eq("user-2"), anyString(), anyString());
    }

    @Test
    void should_create_reply_and_notify_parent_author() {
        when(postMapper.selectById("post-1")).thenReturn(post());
        when(accessService.requireVisibleChannel("ch-1", "user-2")).thenReturn(channel());
        when(accessService.requireInteractionPermission("ch-1", "user-2", ChannelPermissions.COMMENT_CREATE))
                .thenReturn(context());
        Comment parent = new Comment();
        parent.setId("comment-1");
        parent.setPostId("post-1");
        parent.setAuthorId("author-2");
        parent.setParentId(null);
        parent.setStatus(Comment.Status.PUBLISHED.name());
        when(commentMapper.selectById("comment-1")).thenReturn(parent);
        when(commentMapper.insert(any(Comment.class))).thenReturn(1);
        when(postMapper.addCommentCount("post-1", 1)).thenReturn(1);
        when(userMapper.selectById("user-2")).thenReturn(commenter());

        CommentResponse response = commentService.createComment("post-1",
                new CreateCommentRequest("回复内容", "comment-1", null, null, null), "user-2");

        assertThat(response.parentId()).isEqualTo("comment-1");
        verify(notificationService).notifyUser(eq("author-2"), eq(Notification.Type.COMMENT_REPLIED), eq("ch-1"),
                eq("post-1"), anyString(), eq("user-2"), anyString(), anyString());
    }

    @Test
    void should_reject_reply_to_reply() {
        when(postMapper.selectById("post-1")).thenReturn(post());
        when(accessService.requireVisibleChannel("ch-1", "user-2")).thenReturn(channel());
        when(accessService.requireInteractionPermission("ch-1", "user-2", ChannelPermissions.COMMENT_CREATE))
                .thenReturn(context());
        Comment nested = new Comment();
        nested.setId("comment-2");
        nested.setPostId("post-1");
        nested.setAuthorId("author-2");
        nested.setParentId("comment-1");
        nested.setStatus(Comment.Status.PUBLISHED.name());
        when(commentMapper.selectById("comment-2")).thenReturn(nested);

        assertThatThrownBy(() -> commentService.createComment("post-1",
                new CreateCommentRequest("楼中楼", "comment-2", null, null, null), "user-2"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.BUSINESS_RULE_VIOLATION));
        verify(commentMapper, never()).insert(any(Comment.class));
    }

    @Test
    void should_reject_comment_when_member_muted() {
        when(postMapper.selectById("post-1")).thenReturn(post());
        when(accessService.requireVisibleChannel("ch-1", "user-2")).thenReturn(channel());
        when(accessService.requireInteractionPermission("ch-1", "user-2", ChannelPermissions.COMMENT_CREATE))
                .thenThrow(new AppException(ErrorCode.CHANNEL_MUTED));

        assertThatThrownBy(() -> commentService.createComment("post-1",
                new CreateCommentRequest("被禁言", null, null, null, null), "user-2"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode()).isEqualTo(ErrorCode.CHANNEL_MUTED));
        verify(commentMapper, never()).insert(any(Comment.class));
    }

    private Post post() {
        Post post = new Post();
        post.setId("post-1");
        post.setChannelId("ch-1");
        post.setBoardId("board-1");
        post.setAuthorId("author-1");
        post.setContent("帖子正文");
        post.setContentFormat(Post.ContentFormat.PLAIN.name());
        post.setViewCount(1L);
        post.setLikeCount(0L);
        post.setCommentCount(0L);
        post.setShareCount(0L);
        post.setIsPinned(false);
        post.setIsEssence(false);
        post.setStatus(Post.Status.PUBLISHED.name());
        post.setCreatedAt(LocalDateTime.now());
        return post;
    }

    private Channel channel() {
        Channel channel = new Channel();
        channel.setId("ch-1");
        channel.setChannelNo(10_000_001L);
        channel.setName("VRChat 中文社区");
        channel.setOwnerId("owner-1");
        channel.setType(Channel.Type.PUBLIC.name());
        channel.setVisibility(Channel.Visibility.PUBLIC_VISIBLE.name());
        channel.setStatus(Channel.Status.ACTIVE.name());
        return channel;
    }

    private ChannelAccessService.ChannelAccessContext context() {
        ChannelMember member = new ChannelMember();
        member.setId("member-1");
        member.setChannelId("ch-1");
        member.setUserId("user-2");
        member.setRoleId("role-1");
        member.setStatus(ChannelMember.Status.ACTIVE.name());
        Role role = new Role();
        role.setId("role-1");
        role.setChannelId("ch-1");
        role.setName("普通成员");
        role.setPermissions("[\"post.create\",\"comment.create\"]");
        return new ChannelAccessService.ChannelAccessContext(channel(), member, role);
    }

    private User commenter() {
        User user = new User();
        user.setId("user-2");
        user.setUsername("commenter");
        user.setNickname("评论者");
        user.setStatus(User.Status.ACTIVE.name());
        return user;
    }
}
