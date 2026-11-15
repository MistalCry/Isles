package com.isles.manage.service;

import com.isles.channel.audit.AuditLogService;
import com.isles.channel.entity.Channel;
import com.isles.channel.entity.ChannelMember;
import com.isles.channel.mapper.BoardMapper;
import com.isles.channel.mapper.ChannelMapper;
import com.isles.channel.service.ChannelAccessService;
import com.isles.channel.service.ChannelPermissions;
import com.isles.comment.entity.Comment;
import com.isles.comment.mapper.CommentMapper;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.manage.dto.BatchPostRequest;
import com.isles.notification.service.NotificationService;
import com.isles.post.dto.PostResponse;
import com.isles.post.entity.Post;
import com.isles.post.mapper.PostMapper;
import com.isles.post.service.PostService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 内容管理单测：权限/板块范围、置顶/精华、作者删除与管理删除、批量上限、评论删除。
 */
class ContentManageServiceTest {

    private PostMapper postMapper;
    private CommentMapper commentMapper;
    private ChannelMapper channelMapper;
    private BoardMapper boardMapper;
    private ChannelAccessService accessService;
    private PostService postService;
    private AuditLogService auditLogService;
    private NotificationService notificationService;
    private ContentManageService service;

    @BeforeEach
    void setUp() {
        postMapper = mock(PostMapper.class);
        commentMapper = mock(CommentMapper.class);
        channelMapper = mock(ChannelMapper.class);
        boardMapper = mock(BoardMapper.class);
        accessService = mock(ChannelAccessService.class);
        postService = mock(PostService.class);
        auditLogService = mock(AuditLogService.class);
        notificationService = mock(NotificationService.class);
        service = new ContentManageService(postMapper, commentMapper, channelMapper, boardMapper, accessService,
                postService, auditLogService, notificationService);
    }

    @Test
    void should_reject_pin_when_actor_lacks_post_pin_permission() {
        when(postMapper.selectById("post-1")).thenReturn(post());
        when(accessService.requirePostManagePermission("ch-1", "user-2", "board-1", ChannelPermissions.POST_PIN))
                .thenThrow(new AppException(ErrorCode.FORBIDDEN));

        assertThatThrownBy(() -> service.pin("ch-1", "post-1", true, "user-2"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(postMapper, never()).updatePinned(anyString(), eq(true), any());
    }

    @Test
    void should_pin_post_and_write_audit_log() {
        Post post = post();
        when(postMapper.selectById("post-1")).thenReturn(post);
        when(accessService.requirePostManagePermission("ch-1", "admin-1", "board-1", ChannelPermissions.POST_PIN))
                .thenReturn(context("admin-1"));
        when(postMapper.updatePinned(eq("post-1"), eq(true), any(LocalDateTime.class))).thenReturn(1);
        when(postService.toResponses(any(), eq("admin-1"))).thenReturn(List.of(postResponse()));

        PostResponse response = service.pin("ch-1", "post-1", true, "admin-1");

        assertThat(response.id()).isEqualTo("post-1");
        verify(postMapper).updatePinned(eq("post-1"), eq(true), any(LocalDateTime.class));
        verify(auditLogService).record("ch-1", "admin-1", "post.pin", "POST", "post-1");
    }

    @Test
    void should_allow_author_delete_without_admin_permission() {
        Post post = post();
        post.setAuthorId("user-1");
        when(postMapper.selectById("post-1")).thenReturn(post);
        when(postMapper.softDelete("post-1")).thenReturn(1);
        when(channelMapper.addPostCount("ch-1", -1)).thenReturn(1);
        when(boardMapper.addPostCount("board-1", -1)).thenReturn(1);

        service.deletePost("ch-1", "post-1", "user-1");

        verify(accessService, never()).requirePostManagePermission(anyString(), anyString(), anyString(), anyString());
        verify(postMapper).softDelete("post-1");
        verify(channelMapper).addPostCount("ch-1", -1);
        verify(boardMapper).addPostCount("board-1", -1);
        verify(notificationService, never()).notifySystem(anyString(), any(), anyString(), any(), any(), anyString(),
                anyString(), anyMap());
    }

    @Test
    void should_delete_post_as_admin_and_notify_author() {
        Post post = post();
        post.setAuthorId("author-1");
        when(postMapper.selectById("post-1")).thenReturn(post);
        when(accessService.requirePostManagePermission("ch-1", "admin-1", "board-1", ChannelPermissions.POST_DELETE))
                .thenReturn(context("admin-1"));
        when(postMapper.softDelete("post-1")).thenReturn(1);
        when(channelMapper.addPostCount("ch-1", -1)).thenReturn(1);
        when(boardMapper.addPostCount("board-1", -1)).thenReturn(1);

        service.deletePost("ch-1", "post-1", "admin-1");

        verify(postMapper).softDelete("post-1");
        verify(notificationService).notifySystem(eq("author-1"), any(), eq("ch-1"), eq("post-1"), any(),
                anyString(), anyString(), anyMap());
    }

    @Test
    void should_reject_batch_operation_over_limit() {
        when(accessService.requirePermission("ch-1", "admin-1", ChannelPermissions.CONTENT_MANAGE))
                .thenReturn(context("admin-1"));
        List<String> postIds = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            postIds.add("post-" + i);
        }

        assertThatThrownBy(() -> service.batch("ch-1", new BatchPostRequest("PIN", postIds), "admin-1"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(postMapper, never()).updatePinned(anyString(), eq(true), any());
    }

    @Test
    void should_delete_comment_as_author_and_decrement_count() {
        Comment comment = new Comment();
        comment.setId("comment-1");
        comment.setPostId("post-1");
        comment.setAuthorId("user-1");
        comment.setStatus(Comment.Status.PUBLISHED.name());
        when(commentMapper.selectById("comment-1")).thenReturn(comment);
        when(postMapper.selectById("post-1")).thenReturn(post());
        when(commentMapper.updateById(any(Comment.class))).thenReturn(1);
        when(postMapper.addCommentCount("post-1", -1)).thenReturn(1);

        service.deleteComment("comment-1", "user-1");

        verify(commentMapper).updateById(any(Comment.class));
        verify(postMapper).addCommentCount("post-1", -1);
        verify(accessService, never()).requirePostManagePermission(anyString(), anyString(), anyString(), anyString());
    }

    private ChannelAccessService.ChannelAccessContext context(String userId) {
        Channel channel = new Channel();
        channel.setId("ch-1");
        channel.setOwnerId("owner-1");
        channel.setStatus(Channel.Status.ACTIVE.name());
        ChannelMember member = new ChannelMember();
        member.setId("member-" + userId);
        member.setChannelId("ch-1");
        member.setUserId(userId);
        member.setRoleId("role-admin");
        member.setStatus(ChannelMember.Status.ACTIVE.name());
        return new ChannelAccessService.ChannelAccessContext(channel, member, null);
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

    private PostResponse postResponse() {
        return new PostResponse("post-1", "ch-1", "频道", "/avatar.png", "board-1", "综合讨论",
                "author-1", "作者", null, null, "帖子正文", "PLAIN", null, List.of(),
                1, 0, 0, 0, false, true, false, "PUBLISHED", Instant.now());
    }
}
