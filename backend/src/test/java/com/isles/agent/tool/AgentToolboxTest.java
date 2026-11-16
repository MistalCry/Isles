package com.isles.agent.tool;

import com.isles.agent.entity.AgentProfile;
import com.isles.agent.search.PageFetcher;
import com.isles.agent.search.SearchProvider;
import com.isles.agent.search.SearchQuota;
import com.isles.agent.search.SearchResult;
import com.isles.agent.service.AgentMemoryService;
import com.isles.ai.dto.AiContextPost;
import com.isles.ai.mapper.AiContextMapper;
import com.isles.channel.entity.Board;
import com.isles.channel.mapper.BoardMapper;
import com.isles.channel.mapper.ChannelMapper;
import com.isles.comment.dto.CommentResponse;
import com.isles.comment.service.CommentService;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.interaction.service.InteractionService;
import com.isles.post.dto.CreatePostRequest;
import com.isles.post.dto.PostResponse;
import com.isles.post.service.PostService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 代理工具集单元测试（V13）：动作白名单、频道边界、复用现有 Service、记忆写入。
 */
class AgentToolboxTest {

    private AiContextMapper contextMapper;
    private PostService postService;
    private CommentService commentService;
    private InteractionService interactionService;
    private BoardMapper boardMapper;
    private AgentMemoryService memoryService;
    private SearchProvider searchProvider;
    private SearchQuota searchQuota;
    private PageFetcher pageFetcher;
    private AgentToolbox toolbox;
    private AgentToolbox.AgentContext context;

    @BeforeEach
    void setUp() {
        contextMapper = mock(AiContextMapper.class);
        postService = mock(PostService.class);
        commentService = mock(CommentService.class);
        interactionService = mock(InteractionService.class);
        boardMapper = mock(BoardMapper.class);
        memoryService = mock(AgentMemoryService.class);
        searchProvider = mock(SearchProvider.class);
        searchQuota = mock(SearchQuota.class);
        pageFetcher = mock(PageFetcher.class);
        // 默认：联网搜索关闭、配额充足（站内检索用例不受影响）
        when(searchProvider.available()).thenReturn(false);
        when(searchProvider.providerType()).thenReturn("NONE");
        when(searchQuota.tryConsume()).thenReturn(true);
        toolbox = new AgentToolbox(contextMapper, postService, commentService, interactionService, boardMapper,
                mock(ChannelMapper.class), memoryService, searchProvider, searchQuota, pageFetcher);
        AgentProfile profile = new AgentProfile();
        profile.setUserId("agent-1");
        profile.setDisplayName("岛民小蓝");
        context = new AgentToolbox.AgentContext("agent-1", profile, List.of("ch-1"));
    }

    @Test
    void should_reject_action_outside_whitelist() {
        assertThatThrownBy(() -> toolbox.execute("send_dm", Map.of(), context))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void should_reject_channel_outside_allowlist() {
        assertThatThrownBy(() -> toolbox.execute("create_post",
                Map.of("channelId", "ch-999", "content", "正文"), context))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void should_create_post_through_post_service_and_remember_topic() {
        when(boardMapper.selectList(any())).thenReturn(List.of(board()));
        when(postService.createPost(eq("ch-1"), any(CreatePostRequest.class), eq("agent-1")))
                .thenReturn(post("post-1", "露营装备清单"));

        AgentToolbox.ToolOutcome outcome = toolbox.execute("create_post",
                Map.of("content", "说说我最近用的装备", "title", "露营装备清单"), context);

        assertThat(outcome.outputType()).isEqualTo("POST");
        assertThat(outcome.outputId()).isEqualTo("post-1");
        // 走的是现有 Service：敏感词/权限/频率一个都不会被绕过
        verify(postService).createPost(eq("ch-1"), any(CreatePostRequest.class), eq("agent-1"));
        verify(memoryService).remember(eq("agent-1"), eq("TOPIC"), eq("露营装备清单"), eq("post-1"));
    }

    @Test
    void should_fail_create_post_when_channel_has_no_board() {
        when(boardMapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> toolbox.execute("create_post", Map.of("content", "正文"), context))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.BOARD_NOT_FOUND));
        verify(postService, never()).createPost(anyString(), any(), anyString());
    }

    @Test
    void should_comment_through_comment_service() {
        when(commentService.createComment(eq("post-1"), any(), eq("agent-1")))
                .thenReturn(comment("comment-1"));

        AgentToolbox.ToolOutcome outcome = toolbox.execute("comment",
                Map.of("postId", "post-1", "content", "这个清单挺实用"), context);

        assertThat(outcome.outputType()).isEqualTo("COMMENT");
        assertThat(outcome.outputId()).isEqualTo("comment-1");
    }

    @Test
    void should_like_post_and_comment() {
        toolbox.execute("like", Map.of("targetType", "POST", "targetId", "post-1"), context);
        verify(interactionService).likePost("post-1", "agent-1");

        toolbox.execute("like", Map.of("targetType", "COMMENT", "targetId", "comment-1"), context);
        verify(interactionService).likeComment("comment-1", "agent-1");
    }

    @Test
    void should_search_and_summarize_posts() {
        when(contextMapper.searchContextPosts(anyList(), eq("ch-1"), anyInt()))
                .thenReturn(List.of(contextPost("post-1", "露营攻略")));

        AgentToolbox.ToolOutcome outcome = toolbox.execute("search_site_posts",
                Map.of("keyword", "露营", "limit", 3), context);

        assertThat(outcome.summary()).contains("post-1").contains("露营攻略");
        assertThat(outcome.outputType()).isEqualTo("NONE");
    }

    @Test
    void should_remember_and_recall() {
        when(memoryService.remember("agent-1", "OPINION", "我不喜欢吵闹的营地", null)).thenReturn("mem-1");

        AgentToolbox.ToolOutcome remembered = toolbox.execute("remember",
                Map.of("kind", "OPINION", "content", "我不喜欢吵闹的营地"), context);
        assertThat(remembered.summary()).contains("已记住");

        when(memoryService.recall("agent-1", "营地", 5)).thenReturn(List.of());
        assertThat(toolbox.execute("recall", Map.of("query", "营地"), context).summary()).contains("没有相关记忆");
    }

    @Test
    void should_treat_none_as_no_action() {
        AgentToolbox.ToolOutcome outcome = toolbox.execute("none", Map.of(), context);
        assertThat(outcome.outputType()).isEqualTo("NONE");
        verify(postService, never()).createPost(anyString(), any(), anyString());
    }


    // ------------------------------------------------------------------
    // P1 联网搜索 / 抓取
    // ------------------------------------------------------------------

    @Test
    void should_search_web_and_remember_source_urls() {
        when(searchProvider.available()).thenReturn(true);
        when(searchProvider.providerType()).thenReturn("SEARXNG");
        when(searchProvider.search("我的世界 红石", "week", 10)).thenReturn(List.of(
                new SearchResult("红石矿石 - 我的世界", "https://mc.example.com/redstone", "红石基础",
                        "google cse", "2026-11-01"),
                new SearchResult("红石教程", "https://zh.example.com/p/1", "红石电路", "360search", null)));

        AgentToolbox.ToolOutcome outcome = toolbox.execute("web_search",
                Map.of("query", "我的世界 红石", "freshness", "week"), context);

        assertThat(outcome.outputType()).isEqualTo("NONE");
        assertThat(outcome.summary()).contains("google cse").contains("https://mc.example.com/redstone");
        // 命中 url 写入 SOURCE 记忆，供后续去重与追溯
        verify(memoryService).remember(eq("agent-1"), eq("SOURCE"), anyString(),
                eq("https://mc.example.com/redstone"));
        verify(memoryService).remember(eq("agent-1"), eq("SOURCE"), anyString(),
                eq("https://zh.example.com/p/1"));
        verify(searchQuota).tryConsume();
    }

    @Test
    void should_fallback_to_site_search_when_provider_disabled() {
        when(contextMapper.searchContextPosts(anyList(), eq("ch-1"), anyInt()))
                .thenReturn(List.of(contextPost("post-1", "露营攻略")));

        AgentToolbox.ToolOutcome outcome = toolbox.execute("web_search", Map.of("query", "露营"), context);

        assertThat(outcome.summary()).contains("联网搜索未启用").contains("post-1");
        verify(searchProvider, never()).search(anyString(), any(), anyInt());
        verify(memoryService, never()).remember(anyString(), eq("SOURCE"), anyString(), anyString());
    }

    @Test
    void should_fallback_to_site_search_when_web_search_returns_nothing() {
        when(searchProvider.available()).thenReturn(true);
        when(searchProvider.search(anyString(), any(), anyInt())).thenReturn(List.of());
        when(contextMapper.searchContextPosts(anyList(), eq("ch-1"), anyInt())).thenReturn(List.of());

        AgentToolbox.ToolOutcome outcome = toolbox.execute("web_search", Map.of("query", "冷门话题"), context);

        assertThat(outcome.summary()).contains("联网搜索无结果");
    }

    @Test
    void should_fail_loudly_when_quota_exhausted() {
        when(searchQuota.tryConsume()).thenReturn(false);
        when(searchQuota.used()).thenReturn(200);
        when(searchQuota.limit()).thenReturn(200);

        assertThatThrownBy(() -> toolbox.execute("web_search", Map.of("query", "x"), context))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AGENT_SEARCH_QUOTA_EXCEEDED));
        // 配额用尽不能静默降级成站内检索
        verify(searchProvider, never()).search(anyString(), any(), anyInt());
    }

    @Test
    void should_fetch_page_text() {
        when(pageFetcher.fetch("https://mc.example.com/redstone")).thenReturn("红石是 Minecraft 的矿物。");

        AgentToolbox.ToolOutcome outcome = toolbox.execute("fetch_page",
                Map.of("url", "https://mc.example.com/redstone"), context);

        assertThat(outcome.summary()).contains("红石是 Minecraft 的矿物");
        assertThat(outcome.outputType()).isEqualTo("NONE");
    }

    @Test
    void should_reject_fetch_without_url() {
        assertThatThrownBy(() -> toolbox.execute("fetch_page", Map.of(), context))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    private Board board() {
        Board board = new Board();
        board.setId("board-1");
        board.setChannelId("ch-1");
        board.setName("综合");
        board.setSort(0);
        return board;
    }

    private PostResponse post(String id, String title) {
        return new PostResponse(id, "ch-1", "露营岛", null, "board-1", "综合", "agent-1", "岛民小蓝", null,
                title, "正文", "PLAIN", null, List.of(), 0, 0, 0, 0, false, false, false,
                "PUBLISHED", Instant.now(), null, true, true);
    }

    private CommentResponse comment(String id) {
        return new CommentResponse(id, "post-1", null, "agent-1", "岛民小蓝", null, null, null,
                "这个清单挺实用", List.of(), List.of(), 0, false, Instant.now(), List.of(), true, true);
    }

    private AiContextPost contextPost(String id, String title) {
        AiContextPost post = new AiContextPost();
        post.setId(id);
        post.setTitle(title);
        post.setChannelName("露营岛");
        post.setLikeCount(1L);
        post.setCommentCount(2L);
        return post;
    }
}
