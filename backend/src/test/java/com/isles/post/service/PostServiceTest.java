package com.isles.post.service;

import com.isles.channel.entity.Board;
import com.isles.channel.entity.Channel;
import com.isles.channel.entity.ChannelMember;
import com.isles.channel.entity.Role;
import com.isles.channel.audit.AuditLogService;
import com.isles.analytics.service.ChannelViewService;
import com.isles.channel.mapper.BoardMapper;
import com.isles.channel.mapper.ChannelMapper;
import com.isles.channel.service.ChannelAccessService;
import com.isles.channel.service.ChannelPermissions;
import com.isles.common.dto.MediaItem;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.interaction.service.InteractionService;
import com.isles.post.dto.CreatePostRequest;
import com.isles.post.dto.PostResponse;
import com.isles.post.entity.Post;
import com.isles.post.entity.PostMedia;
import com.isles.post.mapper.PostMapper;
import com.isles.post.mapper.PostMediaMapper;
import com.isles.user.entity.User;
import com.isles.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发帖权限/禁言/板块归属与浏览量单元测试。
 */
class PostServiceTest {

    private PostMapper postMapper;
    private PostMediaMapper postMediaMapper;
    private ChannelMapper channelMapper;
    private BoardMapper boardMapper;
    private UserMapper userMapper;
    private ChannelAccessService accessService;
    private InteractionService interactionService;
    private ChannelViewService channelViewService;
    private AuditLogService auditLogService;
    private PostService postService;

    @BeforeEach
    void setUp() {
        postMapper = mock(PostMapper.class);
        postMediaMapper = mock(PostMediaMapper.class);
        channelMapper = mock(ChannelMapper.class);
        boardMapper = mock(BoardMapper.class);
        userMapper = mock(UserMapper.class);
        accessService = mock(ChannelAccessService.class);
        interactionService = mock(InteractionService.class);
        channelViewService = mock(ChannelViewService.class);
        auditLogService = mock(AuditLogService.class);
        postService = new PostService(postMapper, postMediaMapper, channelMapper, boardMapper, userMapper,
                accessService, interactionService, channelViewService, auditLogService);
    }

    @Test
    void should_reject_post_when_not_channel_member() {
        when(accessService.requireInteractionPermission("ch-1", "user-1", ChannelPermissions.POST_CREATE))
                .thenThrow(new AppException(ErrorCode.NOT_JOINED));

        assertThatThrownBy(() -> postService.createPost("ch-1", request("board-1"), "user-1"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode()).isEqualTo(ErrorCode.NOT_JOINED));
        verify(postMapper, never()).insert(any(Post.class));
    }

    @Test
    void should_reject_post_when_member_muted() {
        when(accessService.requireInteractionPermission("ch-1", "user-1", ChannelPermissions.POST_CREATE))
                .thenThrow(new AppException(ErrorCode.CHANNEL_MUTED));

        assertThatThrownBy(() -> postService.createPost("ch-1", request("board-1"), "user-1"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode()).isEqualTo(ErrorCode.CHANNEL_MUTED));
        verify(postMapper, never()).insert(any(Post.class));
    }

    @Test
    void should_reject_post_when_board_belongs_to_other_channel() {
        when(accessService.requireInteractionPermission("ch-1", "user-1", ChannelPermissions.POST_CREATE))
                .thenReturn(context());
        Board otherBoard = board("board-1", "ch-2");
        when(boardMapper.selectById("board-1")).thenReturn(otherBoard);

        assertThatThrownBy(() -> postService.createPost("ch-1", request("board-1"), "user-1"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.BUSINESS_RULE_VIOLATION));
        verify(postMapper, never()).insert(any(Post.class));
    }

    @Test
    void should_create_post_and_update_channel_and_board_counts() {
        when(accessService.requireInteractionPermission("ch-1", "user-1", ChannelPermissions.POST_CREATE))
                .thenReturn(context());
        Board board = board("board-1", "ch-1");
        when(boardMapper.selectById("board-1")).thenReturn(board);
        when(postMapper.insert(any(Post.class))).thenReturn(1);
        List<PostMedia> insertedMedia = new java.util.ArrayList<>();
        when(postMediaMapper.insert(any(PostMedia.class))).thenAnswer(invocation -> {
            insertedMedia.add(invocation.getArgument(0));
            return 1;
        });
        when(channelMapper.addPostCount("ch-1", 1)).thenReturn(1);
        when(boardMapper.addPostCount("board-1", 1)).thenReturn(1);
        when(userMapper.selectBatchIds(anyCollection())).thenReturn(List.of(author()));
        when(channelMapper.selectBatchIds(anyCollection())).thenReturn(List.of(context().channel()));
        when(boardMapper.selectBatchIds(anyCollection())).thenReturn(List.of(board));
        when(postMediaMapper.selectList(any())).thenAnswer(invocation -> insertedMedia);
        when(interactionService.likedPostIds(any(), any())).thenReturn(Set.of());

        PostResponse response = postService.createPost("ch-1", request("board-1"), "user-1");

        assertThat(response.content()).isEqualTo("本周 VRChat 活动合集");
        assertThat(response.channelId()).isEqualTo("ch-1");
        assertThat(response.boardName()).isEqualTo("综合讨论");
        assertThat(response.authorNickname()).isEqualTo("VR 爱好者");
        assertThat(response.media()).hasSize(1);
        verify(postMapper).insert(any(Post.class));
        verify(postMediaMapper).insert(any(PostMedia.class));
        verify(channelMapper).addPostCount("ch-1", 1);
        verify(boardMapper).addPostCount("board-1", 1);
    }

    @Test
    void should_increment_view_count_when_get_post_detail() {
        Post post = post();
        when(postMapper.selectById("post-1")).thenReturn(post);
        when(accessService.requireVisibleChannel("ch-1", null)).thenReturn(context().channel());
        when(userMapper.selectBatchIds(anyCollection())).thenReturn(List.of(author()));
        when(channelMapper.selectBatchIds(anyCollection())).thenReturn(List.of(context().channel()));
        when(boardMapper.selectBatchIds(anyCollection())).thenReturn(List.of(board("board-1", "ch-1")));
        when(postMediaMapper.selectList(any())).thenReturn(List.of());
        when(interactionService.likedPostIds(any(), any())).thenReturn(Set.of());

        PostResponse response = postService.getPostDetail("post-1", null);

        assertThat(response.viewCount()).isEqualTo(6L);
        verify(interactionService).incrementViewCount("post-1");
    }

    private CreatePostRequest request(String boardId) {
        return new CreatePostRequest(boardId, "本周 VRChat 活动合集", "本周 VRChat 活动合集", "PLAIN", null,
                List.of(new MediaItem("IMAGE", "/uploads/2026/11/a.jpg", "/uploads/2026/11/a_thumb.jpg",
                        800, 600, null)));
    }

    private ChannelAccessService.ChannelAccessContext context() {
        ChannelMember member = new ChannelMember();
        member.setId("member-1");
        member.setChannelId("ch-1");
        member.setUserId("user-1");
        member.setRoleId("role-1");
        member.setStatus(ChannelMember.Status.ACTIVE.name());
        Role role = new Role();
        role.setId("role-1");
        role.setChannelId("ch-1");
        role.setName("普通成员");
        role.setPermissions("[\"post.create\",\"comment.create\"]");
        return new ChannelAccessService.ChannelAccessContext(channel(), member, role);
    }

    private Channel channel() {
        Channel channel = new Channel();
        channel.setId("ch-1");
        channel.setChannelNo(10_000_001L);
        channel.setName("VRChat 中文社区");
        channel.setAvatarUrl("/uploads/av.png");
        channel.setOwnerId("owner-1");
        channel.setType(Channel.Type.PUBLIC.name());
        channel.setVisibility(Channel.Visibility.PUBLIC_VISIBLE.name());
        channel.setStatus(Channel.Status.ACTIVE.name());
        channel.setMemberCount(10L);
        channel.setPostCount(5L);
        channel.setCreatedAt(LocalDateTime.now());
        return channel;
    }

    private Board board(String id, String channelId) {
        Board board = new Board();
        board.setId(id);
        board.setChannelId(channelId);
        board.setName("综合讨论");
        board.setSort(0);
        board.setPostCount(1L);
        board.setCreatedAt(LocalDateTime.now());
        return board;
    }

    private User author() {
        User user = new User();
        user.setId("user-1");
        user.setUsername("vrfan");
        user.setNickname("VR 爱好者");
        user.setAvatarUrl("/uploads/avatar.png");
        user.setStatus(User.Status.ACTIVE.name());
        return user;
    }

    private Post post() {
        Post post = new Post();
        post.setId("post-1");
        post.setChannelId("ch-1");
        post.setBoardId("board-1");
        post.setAuthorId("user-1");
        post.setContent("内容");
        post.setContentFormat(Post.ContentFormat.PLAIN.name());
        post.setViewCount(5L);
        post.setLikeCount(2L);
        post.setCommentCount(1L);
        post.setShareCount(0L);
        post.setIsPinned(false);
        post.setIsEssence(false);
        post.setStatus(Post.Status.PUBLISHED.name());
        post.setCreatedAt(LocalDateTime.now());
        return post;
    }
}
