package com.isles.ai.tool;

import com.isles.ai.dto.AiContextPost;
import com.isles.ai.mapper.AiContextMapper;
import com.isles.ai.provider.AiToolDefinition;
import com.isles.ai.service.AiContextService;
import com.isles.common.json.JsonSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具注册表单元测试（docs/05 §2.13）：schema 暴露范围、检索执行、参数容错与可见性。
 */
class AiToolRegistryTest {

    private AiContextService contextService;
    private AiContextMapper contextMapper;
    private AiToolRegistry registry;

    @BeforeEach
    void setUp() {
        contextService = mock(AiContextService.class);
        contextMapper = mock(AiContextMapper.class);
        registry = new AiToolRegistry(contextService, contextMapper, new JsonSupport());
    }

    @Test
    void should_expose_channel_search_only_in_channel_scope() {
        List<AiToolDefinition> channelTools = registry.definitions(true);
        List<AiToolDefinition> globalTools = registry.definitions(false);

        assertThat(channelTools).extracting(AiToolDefinition::name)
                .containsExactly(AiToolRegistry.SEARCH_CHANNEL_POSTS, AiToolRegistry.SEARCH_GLOBAL_POSTS,
                        AiToolRegistry.GET_POST_DETAIL);
        assertThat(globalTools).extracting(AiToolDefinition::name)
                .containsExactly(AiToolRegistry.SEARCH_GLOBAL_POSTS, AiToolRegistry.GET_POST_DETAIL);
    }

    @Test
    void should_search_global_posts_and_return_hits() {
        when(contextService.retrieve("露营", null, 3)).thenReturn(List.of(post("p1", "露营装备清单")));

        AiToolRegistry.ToolOutcome outcome = registry.execute(AiToolRegistry.SEARCH_GLOBAL_POSTS,
                "{\"query\":\"露营\",\"limit\":3}", null);

        assertThat(outcome.posts()).hasSize(1);
        assertThat(outcome.posts().get(0).getId()).isEqualTo("p1");
        assertThat(outcome.content()).contains("露营装备清单").contains("p1").contains("\"total\":1");
    }

    @Test
    void should_clamp_limit_to_maximum() {
        when(contextService.retrieve(eq("岛"), eq("ch-1"), anyInt())).thenReturn(List.of());

        registry.execute(AiToolRegistry.SEARCH_CHANNEL_POSTS, "{\"query\":\"岛\",\"limit\":100}", "ch-1");

        verify(contextService).retrieve("岛", "ch-1", 8);
    }

    @Test
    void should_reject_channel_search_outside_channel() {
        AiToolRegistry.ToolOutcome outcome = registry.execute(AiToolRegistry.SEARCH_CHANNEL_POSTS,
                "{\"query\":\"岛\"}", null);

        assertThat(outcome.posts()).isEmpty();
        assertThat(outcome.content()).contains("not_in_channel");
        verify(contextService, org.mockito.Mockito.never()).retrieve(any(), any(), anyInt());
    }

    @Test
    void should_return_post_detail_when_visible() {
        when(contextMapper.selectContextPostById("p1", "ch-1")).thenReturn(post("p1", "露营装备清单"));

        AiToolRegistry.ToolOutcome outcome = registry.execute(AiToolRegistry.GET_POST_DETAIL,
                "{\"postId\":\"p1\"}", "ch-1");

        assertThat(outcome.posts()).hasSize(1);
        assertThat(outcome.content()).contains("露营装备清单").contains("/posts/p1");
    }

    @Test
    void should_report_missing_post_id_and_invisible_post() {
        assertThat(registry.execute(AiToolRegistry.GET_POST_DETAIL, "{}", null).content())
                .contains("invalid_argument");

        when(contextMapper.selectContextPostById("p9", null)).thenReturn(null);
        assertThat(registry.execute(AiToolRegistry.GET_POST_DETAIL, "{\"postId\":\"p9\"}", null).content())
                .contains("post_not_found");
    }

    @Test
    void should_tolerate_broken_arguments_and_unknown_tool() {
        when(contextService.retrieve("", null, 5)).thenReturn(List.of());

        // 非法 JSON 按空参数处理，不抛异常
        assertThat(registry.execute(AiToolRegistry.SEARCH_GLOBAL_POSTS, "not-json", null).posts()).isEmpty();
        assertThat(registry.execute("delete_everything", "{}", null).content()).contains("unknown_tool");
    }

    private AiContextPost post(String id, String title) {
        AiContextPost post = new AiContextPost();
        post.setId(id);
        post.setChannelId("ch-1");
        post.setChannelName("露营岛");
        post.setTitle(title);
        post.setContent("<p>帐篷与睡袋的选购经验</p>");
        post.setLikeCount(12L);
        post.setCommentCount(3L);
        return post;
    }
}
