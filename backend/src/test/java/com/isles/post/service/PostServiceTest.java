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
import com.isles.channel.service.MentionService;
import com.isles.channel.service.ChannelPermissions;
import com.isles.common.dto.MediaItem;
import com.isles.common.error.AppException;
import com.isles.comment.entity.Comment;
import com.isles.comment.mapper.CommentMapper;
import com.isles.common.error.ErrorCode;
import com.isles.interaction.service.InteractionService;
import com.isles.post.dto.CreatePostRequest;
import com.isles.post.dto.PostResponse;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.isles.post.entity.Post;
import com.isles.post.entity.PostMedia;
import com.isles.post.mapper.PostMapper;
import com.isles.post.mapper.PostMediaMapper;
import com.isles.user.entity.User;
import com.isles.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;

/**
 * 发帖权限/禁言/板块归属与浏览量单元测试。
 */
class PostServiceTest {

    private PostMapper postMapper;
    private PostMediaMapper postMediaMapper;
    private CommentMapper commentMapper;
    private ChannelMapper channelMapper;
    private BoardMapper boardMapper;
    private UserMapper userMapper;
    private ChannelAccessService accessService;
    private InteractionService interactionService;
    private ChannelViewService channelViewService;
    private AuditLogService auditLogService;
    private com.isles.platform.wordfilter.SensitiveWordService sensitiveWordService;
    private PostService postService;

    @BeforeEach
    void setUp() {
        postMapper = mock(PostMapper.class);
        postMediaMapper = mock(PostMediaMapper.class);
        commentMapper = mock(CommentMapper.class);
        channelMapper = mock(ChannelMapper.class);
        boardMapper = mock(BoardMapper.class);
        userMapper = mock(UserMapper.class);
        accessService = mock(ChannelAccessService.class);
        interactionService = mock(InteractionService.class);
        channelViewService = mock(ChannelViewService.class);
        auditLogService = mock(AuditLogService.class);
        sensitiveWordService = mock(com.isles.platform.wordfilter.SensitiveWordService.class);
        // 默认：不命中，原文入库（REVIEW 语义）；需要 MASK/BLOCK 的用例单独打桩
        when(sensitiveWordService.inspect(any(), anyBoolean())).thenAnswer(invocation ->
                new com.isles.platform.wordfilter.SensitiveWordService.Inspection(
                        invocation.getArgument(0), List.of(), "REVIEW"));
        postService = new PostService(postMapper, postMediaMapper, commentMapper,
                channelMapper, boardMapper, userMapper,
                accessService, interactionService, channelViewService, auditLogService, mock(MentionService.class),
                sensitiveWordService);
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


    @Test
    void should_clamp_pinned_posts_to_twenty() {
        when(postMapper.selectChannelPinned("ch-1", null, 20)).thenReturn(List.of());

        postService.listPinnedPosts("ch-1", 100, null);

        verify(postMapper).selectChannelPinned("ch-1", null, 20);
    }

    @Test
    void should_include_top_comment_when_building_responses() {
        when(commentMapper.selectTopLikedByPostIds(List.of("post-1")))
                .thenReturn(List.of(comment("comment-1", "user-2")));
        when(userMapper.selectBatchIds(anyCollection())).thenReturn(List.of(author(), otherAuthor()));
        when(channelMapper.selectBatchIds(anyCollection())).thenReturn(List.of(channel()));
        when(boardMapper.selectBatchIds(anyCollection())).thenReturn(List.of(board("board-1", "ch-1")));
        when(postMediaMapper.selectList(any())).thenReturn(List.of());
        when(interactionService.likedPostIds(any(), any())).thenReturn(Set.of());
        when(interactionService.likedCommentIds(any(), any())).thenReturn(Set.of("comment-1"));

        List<PostResponse> responses = postService.toResponses(List.of(post()), "viewer-1");

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).topComment()).isNotNull();
        assertThat(responses.get(0).topComment().id()).isEqualTo("comment-1");
        assertThat(responses.get(0).topComment().authorNickname()).isEqualTo("评论者");
        assertThat(responses.get(0).topComment().likeCount()).isEqualTo(3L);
        assertThat(responses.get(0).topComment().liked()).isTrue();
    }

    @Test
    void should_return_null_top_comment_when_post_has_no_comment() {
        when(commentMapper.selectTopLikedByPostIds(List.of("post-1"))).thenReturn(List.of());
        when(userMapper.selectBatchIds(anyCollection())).thenReturn(List.of(author()));
        when(channelMapper.selectBatchIds(anyCollection())).thenReturn(List.of(channel()));
        when(boardMapper.selectBatchIds(anyCollection())).thenReturn(List.of(board("board-1", "ch-1")));
        when(postMediaMapper.selectList(any())).thenReturn(List.of());
        when(interactionService.likedPostIds(any(), any())).thenReturn(Set.of());

        List<PostResponse> responses = postService.toResponses(List.of(post()), null);

        assertThat(responses.get(0).topComment()).isNull();
        verify(interactionService, never()).likedCommentIds(any(), any());
    }

    private Comment comment(String id, String authorId) {
        Comment comment = new Comment();
        comment.setId(id);
        comment.setPostId("post-1");
        comment.setAuthorId(authorId);
        comment.setContent("最高赞评论");
        comment.setLikeCount(3L);
        comment.setStatus(Comment.Status.PUBLISHED.name());
        comment.setCreatedAt(LocalDateTime.now());
        return comment;
    }

    private User otherAuthor() {
        User user = new User();
        user.setId("user-2");
        user.setUsername("commenter");
        user.setNickname("评论者");
        user.setStatus(User.Status.ACTIVE.name());
        return user;
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


    /**
     * 移除封面 / 清空标题必须真正落库。
     *
     * <p>MyBatis-Plus 的 {@code updateById} 默认跳过 null 字段，仅把内存值置 null 是「空转」：
     * 保存成功但库里仍是旧值。这里同时锁两层——服务层确实把值置 null，实体字段确实声明了 ALWAYS。
     */
    @Test
    void should_clear_cover_and_title_when_blank() {
        Post existing = post();
        existing.setTitle("旧标题");
        existing.setCoverUrl("/uploads/old-cover.jpg");
        when(postMapper.selectById("post-1")).thenReturn(existing);
        when(postMapper.updateById(any(Post.class))).thenReturn(1);
        when(userMapper.selectBatchIds(anyCollection())).thenReturn(List.of(author()));
        when(channelMapper.selectBatchIds(anyCollection())).thenReturn(List.of(channel()));
        when(boardMapper.selectBatchIds(anyCollection())).thenReturn(List.of(board("board-1", "ch-1")));
        when(postMediaMapper.selectList(any())).thenReturn(List.of());
        when(interactionService.likedPostIds(any(), any())).thenReturn(Set.of());
        when(commentMapper.selectTopLikedByPostIds(any())).thenReturn(List.of());

        postService.updatePost("post-1", new com.isles.post.dto.UpdatePostRequest("", "内容", "PLAIN", "", List.of()),
                "user-1");

        ArgumentCaptor<Post> captor = ArgumentCaptor.forClass(Post.class);
        verify(postMapper).updateById(captor.capture());
        assertThat(captor.getValue().getCoverUrl()).isNull();
        assertThat(captor.getValue().getTitle()).isNull();

        // 字段策略回归锁：去掉 ALWAYS 后上面的 null 会被 updateById 静默丢弃
        assertThat(updateStrategyOf("coverUrl")).isEqualTo(FieldStrategy.ALWAYS);
        assertThat(updateStrategyOf("title")).isEqualTo(FieldStrategy.ALWAYS);
    }


    /** 作者自己刷新详情不应增加浏览量（热度公式不被自刷污染）。 */
    @Test
    void should_not_count_view_when_author_views_own_post() {
        when(postMapper.selectById("post-1")).thenReturn(post());
        mockResponseDependencies();

        PostResponse response = postService.getPostDetail("post-1", "user-1");

        verify(interactionService, never()).incrementViewCount("post-1");
        verify(channelViewService, never()).recordView(any(), any(), any());
        assertThat(response.viewCount()).isEqualTo(5L);
    }

    /** 同一会话 30 分钟内重复访问只计一次。 */
    @Test
    void should_count_view_once_within_dedupe_window() {
        when(postMapper.selectById("post-1")).thenReturn(post());
        mockResponseDependencies();

        postService.getPostDetail("post-1", "user-2");
        postService.getPostDetail("post-1", "user-2");
        postService.getPostDetail("post-1", "user-2");

        verify(interactionService, times(1)).incrementViewCount("post-1");
        verify(channelViewService, times(1)).recordView("ch-1", "post-1", "user-2");
    }

    /** 不同用户各自计一次。 */
    @Test
    void should_count_view_for_different_viewers() {
        when(postMapper.selectById("post-1")).thenReturn(post());
        mockResponseDependencies();

        postService.getPostDetail("post-1", "user-2");
        postService.getPostDetail("post-1", "user-3");

        verify(interactionService, times(2)).incrementViewCount("post-1");
    }


    /** 我的帖子：page/size 偏移分页，返回与帖子流同构的 PostResponse。 */
    @Test
    void should_list_my_posts_with_offset_paging() {
        when(postMapper.countUserPosts("user-1", "ch-1")).thenReturn(3L);
        when(postMapper.selectUserPosts("user-1", "ch-1", 20, 0)).thenReturn(List.of(post()));
        mockResponseDependencies();

        com.isles.post.dto.UserPostPage page = postService.listMyPosts("user-1", "ch-1", 1, 20);

        assertThat(page.total()).isEqualTo(3L);
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(20);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).id()).isEqualTo("post-1");
        verify(postMapper).selectUserPosts("user-1", "ch-1", 20, 0);
    }

    @Test
    void should_clamp_my_posts_size() {
        when(postMapper.countUserPosts("user-1", null)).thenReturn(0L);
        when(postMapper.selectUserPosts("user-1", null, 50, 100)).thenReturn(List.of());

        com.isles.post.dto.UserPostPage page = postService.listMyPosts("user-1", null, 3, 500);

        assertThat(page.size()).isEqualTo(50);
        verify(postMapper).selectUserPosts("user-1", null, 50, 100);
    }

    private void mockResponseDependencies() {
        when(userMapper.selectBatchIds(anyCollection())).thenReturn(List.of(author()));
        when(channelMapper.selectBatchIds(anyCollection())).thenReturn(List.of(channel()));
        when(boardMapper.selectBatchIds(anyCollection())).thenReturn(List.of(board("board-1", "ch-1")));
        when(postMediaMapper.selectList(any())).thenReturn(List.of());
        when(interactionService.likedPostIds(any(), any())).thenReturn(Set.of());
        when(commentMapper.selectTopLikedByPostIds(any())).thenReturn(List.of());
    }

    private FieldStrategy updateStrategyOf(String fieldName) {
        try {
            Field field = Post.class.getDeclaredField(fieldName);
            TableField annotation = field.getAnnotation(TableField.class);
            return annotation == null ? null : annotation.updateStrategy();
        } catch (NoSuchFieldException ex) {
            throw new AssertionError("字段不存在：" + fieldName, ex);
        }
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
