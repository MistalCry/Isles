package com.isles.ai.service;

import com.isles.ai.config.AiProperties;
import com.isles.ai.dto.AiContextPost;
import com.isles.ai.entity.AiMessage;
import com.isles.ai.mapper.AiContextMapper;
import com.isles.ai.provider.AiChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 检索上下文拼装单元测试（docs/03 §7 AI 上下文检索）：
 * 关键词提取、来源引用结构、频道范围限定、无命中兜底、图片透传到最终用户消息。
 */
class AiContextServiceTest {

    private AiContextMapper contextMapper;
    private AiContextService service;

    @BeforeEach
    void setUp() {
        contextMapper = mock(AiContextMapper.class);
        service = new AiContextService(contextMapper, AiProperties.defaults());
    }

    @Test
    void should_extract_keywords_and_filter_stop_words() {
        List<String> keywords = service.extractKeywords("VRChat 活动 推荐");

        assertThat(keywords).containsExactly("VRChat", "活动", "推荐");
    }

    @Test
    void should_fallback_to_whole_question_when_only_short_tokens() {
        List<String> keywords = service.extractKeywords("a b 什么");

        assertThat(keywords).hasSize(1);
        assertThat(keywords.get(0)).isEqualTo("a b 什么");
    }

    @Test
    void should_build_prompt_with_sources_and_global_scope() {
        when(contextMapper.searchContextPosts(any(), isNull(), anyInt())).thenReturn(List.of(post("p1", "ch-1", "VRChat 活动")));

        AiContextService.AiPrompt prompt = service.buildPrompt(new AiContextService.AiPromptRequest(
                "GLOBAL", null, null, "VRChat 有什么活动", List.of(), List.of(), false, null));

        assertThat(prompt.sources()).hasSize(1);
        assertThat(prompt.sources().get(0).url()).isEqualTo("/posts/p1");
        assertThat(prompt.sources().get(0).channelId()).isEqualTo("ch-1");
        assertThat(prompt.messages()).isNotEmpty();
        AiChatMessage system = prompt.messages().get(0);
        assertThat(system.role()).isEqualTo("system");
        assertThat(system.content()).contains("【社区内容检索结果】").contains("VRChat 活动");
        AiChatMessage last = prompt.messages().get(prompt.messages().size() - 1);
        assertThat(last.role()).isEqualTo("user");
        assertThat(last.content()).isEqualTo("VRChat 有什么活动");
    }

    @Test
    void should_scope_retrieval_to_channel_for_channel_assistant() {
        when(contextMapper.searchContextPosts(any(), eq("ch-1"), anyInt())).thenReturn(List.of(post("p1", "ch-1", "频道内帖子")));

        AiContextService.AiPrompt prompt = service.buildPrompt(new AiContextService.AiPromptRequest(
                "CHANNEL", "ch-1", "测试频道", "有什么新内容", List.of(), List.of(), false, "你是热心岛民"));

        assertThat(prompt.sources()).hasSize(1);
        assertThat(prompt.messages().get(0).content()).contains("测试频道").contains("热心岛民");
    }

    @Test
    void should_fallback_to_recent_posts_when_keyword_hits_empty() {
        when(contextMapper.searchContextPosts(any(), any(), anyInt())).thenReturn(List.of());
        when(contextMapper.selectRecentPosts(any(), any(), anyInt())).thenReturn(List.of(post("p2", null, "兜底内容")));

        AiContextService.AiPrompt prompt = service.buildPrompt(new AiContextService.AiPromptRequest(
                "GLOBAL", null, null, "完全不相关的问题", List.of(), List.of(), false, null));

        assertThat(prompt.sources()).hasSize(1);
        assertThat(prompt.sources().get(0).id()).isEqualTo("p2");
    }

    @Test
    void should_say_no_content_when_retrieval_empty() {
        when(contextMapper.searchContextPosts(any(), any(), anyInt())).thenReturn(List.of());
        when(contextMapper.selectRecentPosts(any(), any(), anyInt())).thenReturn(List.of());

        AiContextService.AiPrompt prompt = service.buildPrompt(new AiContextService.AiPromptRequest(
                "GLOBAL", null, null, "没有内容的问题", List.of(), List.of(), false, null));

        assertThat(prompt.sources()).isEmpty();
        assertThat(prompt.messages().get(0).content()).contains("未检索到相关社区内容");
    }

    @Test
    void should_attach_images_to_final_user_message() {
        when(contextMapper.searchContextPosts(any(), any(), anyInt())).thenReturn(List.of());

        AiContextService.AiPrompt prompt = service.buildPrompt(new AiContextService.AiPromptRequest(
                "GLOBAL", null, null, "看图", List.of("data:image/png;base64,AAAA"), List.of(), false, null));

        AiChatMessage last = prompt.messages().get(prompt.messages().size() - 1);
        assertThat(last.imageUrls()).containsExactly("data:image/png;base64,AAAA");
    }

    @Test
    void should_include_history_excluding_current_question() {
        when(contextMapper.searchContextPosts(any(), any(), anyInt())).thenReturn(List.of());
        AiMessage previous = new AiMessage();
        previous.setRole("USER");
        previous.setContent("上一个问题");
        previous.setStatus("COMPLETED");

        AiContextService.AiPrompt prompt = service.buildPrompt(new AiContextService.AiPromptRequest(
                "GLOBAL", null, null, "新问题", List.of(), List.of(previous), false, null));

        List<AiChatMessage> messages = prompt.messages();
        assertThat(messages).hasSize(3);
        assertThat(messages.get(1).content()).isEqualTo("上一个问题");
        ArgumentCaptor<List<String>> ignored = ArgumentCaptor.forClass(List.class);
        verify(contextMapper).searchContextPosts(ignored.capture(), isNull(), anyInt());
    }

    private AiContextPost post(String id, String channelId, String content) {
        AiContextPost post = new AiContextPost();
        post.setId(id);
        post.setChannelId(channelId);
        post.setChannelName("测试频道");
        post.setAuthorId("author-1");
        post.setTitle(content);
        post.setContent(content + " 的正文摘要");
        post.setLikeCount(2L);
        post.setCommentCount(1L);
        post.setViewCount(10L);
        post.setCreatedAt(LocalDateTime.now());
        return post;
    }
}
