package com.isles.search.service;

import com.isles.channel.entity.Board;
import com.isles.channel.entity.Channel;
import com.isles.channel.mapper.BoardMapper;
import com.isles.channel.mapper.ChannelMapper;
import com.isles.channel.service.ChannelAccessService;
import com.isles.common.pagination.PageResult;
import com.isles.post.entity.Post;
import com.isles.post.entity.PostMedia;
import com.isles.post.mapper.PostMapper;
import com.isles.post.mapper.PostMediaMapper;
import com.isles.post.service.PostService;
import com.isles.search.dto.SearchItemResponse;
import com.isles.search.mapper.SearchHistoryMapper;
import com.isles.search.mapper.SearchLogMapper;
import com.isles.user.entity.User;
import com.isles.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 搜索服务单元测试（docs/03 §4.10）：结果补首图与封面，且媒体/关联数据批量查询（无 N+1）。
 */
class SearchServiceTest {

    private ChannelMapper channelMapper;
    private PostMapper postMapper;
    private PostMediaMapper postMediaMapper;
    private UserMapper userMapper;
    private BoardMapper boardMapper;
    private SearchHistoryMapper searchHistoryMapper;
    private SearchLogMapper searchLogMapper;
    private SearchService searchService;

    @BeforeEach
    void setUp() {
        channelMapper = mock(ChannelMapper.class);
        postMapper = mock(PostMapper.class);
        postMediaMapper = mock(PostMediaMapper.class);
        userMapper = mock(UserMapper.class);
        boardMapper = mock(BoardMapper.class);
        searchHistoryMapper = mock(SearchHistoryMapper.class);
        searchLogMapper = mock(SearchLogMapper.class);
        searchService = new SearchService(channelMapper, postMapper, postMediaMapper, userMapper, boardMapper,
                searchHistoryMapper, searchLogMapper, mock(SearchHotwordService.class),
                mock(ChannelAccessService.class), mock(PostService.class));
    }

    @Test
    void should_return_cover_and_first_media_without_n_plus_one() {
        Post withMedia = post("p1", "/uploads/cover-p1.jpg");
        Post withoutMedia = post("p2", null);
        when(postMapper.searchVisiblePosts("%camping%", null, 11)).thenReturn(List.of(withMedia, withoutMedia));
        when(channelMapper.selectBatchIds(anyCollection())).thenReturn(List.of(channel("ch-1")));
        when(userMapper.selectBatchIds(anyCollection())).thenReturn(List.of(author("u-1")));
        when(boardMapper.selectBatchIds(anyCollection())).thenReturn(List.of(board("b-1", "ch-1")));
        // 已按 sort 升序返回：p1 的首图是 sort=0，p2 没有媒体
        when(postMediaMapper.selectByPostIds(anyCollection())).thenReturn(List.of(
                media("p1", 0, "/uploads/p1-0.jpg"),
                media("p1", 1, "/uploads/p1-1.jpg")));

        PageResult<SearchItemResponse> page = searchService.search("camping", "post", "latest", null, 10, "u-2");

        assertThat(page.items()).hasSize(2);
        SearchItemResponse first = page.items().get(0);
        assertThat(first.coverUrl()).isEqualTo("/uploads/cover-p1.jpg");
        assertThat(first.media()).hasSize(1);
        assertThat(first.media().get(0).url()).isEqualTo("/uploads/p1-0.jpg");
        assertThat(first.media().get(0).thumbUrl()).isEqualTo("/uploads/p1-0-thumb.jpg");
        assertThat(first.channelName()).isEqualTo("露营岛");
        assertThat(first.authorNickname()).isEqualTo("岛民");

        SearchItemResponse second = page.items().get(1);
        assertThat(second.coverUrl()).isNull();
        assertThat(second.media()).isEmpty();

        // 批量：三个关联查询各一次 + 媒体一次，而不是每个帖子各查一次
        verify(postMediaMapper, times(1)).selectByPostIds(anyCollection());
        verify(channelMapper, times(1)).selectBatchIds(anyCollection());
        verify(userMapper, times(1)).selectBatchIds(anyCollection());
        verify(boardMapper, times(1)).selectBatchIds(anyCollection());
    }

    @Test
    void should_not_query_media_when_page_empty() {
        when(postMapper.searchVisiblePosts("%nothing%", null, 11)).thenReturn(List.of());

        PageResult<SearchItemResponse> page = searchService.search("nothing", "post", "latest", null, 10, null);

        assertThat(page.items()).isEmpty();
        verify(postMediaMapper, org.mockito.Mockito.never()).selectByPostIds(any());
        verify(searchLogMapper).insert(org.mockito.ArgumentMatchers.<com.isles.search.entity.SearchLog>any());
    }

    // ------------------------------------------------------------------
    // 构造
    // ------------------------------------------------------------------


    /** 搜索结果也是帖子卡片：AI 标识必须与 PostResponse 一致。 */
    @Test
    void should_expose_ai_flags_in_search_items() {
        Post agentPost = post("p1", null);
        agentPost.setIsAiGenerated(true);
        when(postMapper.searchVisiblePosts("%camping%", null, 11)).thenReturn(List.of(agentPost));
        when(channelMapper.selectBatchIds(anyCollection())).thenReturn(List.of(channel("ch-1")));
        User agent = author("u-1");
        agent.setIsAgent(true);
        when(userMapper.selectBatchIds(anyCollection())).thenReturn(List.of(agent));
        when(boardMapper.selectBatchIds(anyCollection())).thenReturn(List.of(board("b-1", "ch-1")));
        when(postMediaMapper.selectByPostIds(anyCollection())).thenReturn(List.of());

        PageResult<SearchItemResponse> page = searchService.search("camping", "post", "latest", null, 10, "u-2");

        assertThat(page.items().get(0).isAiGenerated()).isTrue();
        assertThat(page.items().get(0).authorIsAgent()).isTrue();
    }

    private Post post(String id, String coverUrl) {
        Post post = new Post();
        post.setId(id);
        post.setChannelId("ch-1");
        post.setBoardId("b-1");
        post.setAuthorId("u-1");
        post.setTitle("露营装备");
        post.setContent("帐篷与睡袋");
        post.setContentFormat(Post.ContentFormat.PLAIN.name());
        post.setCoverUrl(coverUrl);
        post.setLikeCount(3L);
        post.setCommentCount(1L);
        post.setStatus(Post.Status.PUBLISHED.name());
        post.setCreatedAt(LocalDateTime.now());
        return post;
    }

    private PostMedia media(String postId, int sort, String url) {
        PostMedia media = new PostMedia();
        media.setId("m-" + postId + "-" + sort);
        media.setPostId(postId);
        media.setMediaType(PostMedia.MediaType.IMAGE.name());
        media.setUrl(url);
        media.setThumbUrl(url.replace(".jpg", "-thumb.jpg"));
        media.setWidth(800);
        media.setHeight(600);
        media.setSort(sort);
        return media;
    }

    private Channel channel(String id) {
        Channel channel = new Channel();
        channel.setId(id);
        channel.setName("露营岛");
        channel.setAvatarUrl("/uploads/ch.png");
        channel.setMemberCount(10L);
        channel.setCreatedAt(LocalDateTime.now());
        return channel;
    }

    private User author(String id) {
        User user = new User();
        user.setId(id);
        user.setUsername("islander");
        user.setNickname("岛民");
        user.setStatus(User.Status.ACTIVE.name());
        return user;
    }

    private Board board(String id, String channelId) {
        Board board = new Board();
        board.setId(id);
        board.setChannelId(channelId);
        board.setName("综合");
        return board;
    }
}
