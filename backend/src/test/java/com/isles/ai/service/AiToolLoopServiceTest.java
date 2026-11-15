package com.isles.ai.service;

import com.isles.ai.dto.AiContextPost;
import com.isles.ai.provider.AiChatMessage;
import com.isles.ai.provider.AiChatRequest;
import com.isles.ai.provider.AiChatResult;
import com.isles.ai.provider.AiProvider;
import com.isles.ai.provider.AiProviderException;
import com.isles.ai.provider.AiToolCall;
import com.isles.ai.provider.AiToolDefinition;
import com.isles.ai.provider.AiUsage;
import com.isles.ai.tool.AiToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具循环单元测试（docs/05 §2.13）：
 * 轮次上限、来源命中合并、用户中断、总超时、阻塞式执行器。
 */
class AiToolLoopServiceTest {

    private AiToolRegistry registry;
    private AiToolLoopService service;
    private List<AiChatRequest> requests;

    @BeforeEach
    void setUp() {
        registry = mock(AiToolRegistry.class);
        service = new AiToolLoopService(registry);
        requests = new ArrayList<>();
    }

    @Test
    void should_execute_tool_then_answer_and_collect_hits() {
        when(registry.execute(eq("search_channel_posts"), anyString(), eq("ch-1")))
                .thenReturn(new AiToolRegistry.ToolOutcome("{\"total\":1}", List.of(post("p1"))));

        AiToolLoopService.ToolLoopResult result = service.run(base(), definitions(), "ch-1",
                future(), () -> false, request -> {
                    requests.add(request);
                    if (requests.size() == 1) {
                        return new AiToolLoopService.ToolTurn("", "", AiUsage.empty(),
                                List.of(new AiToolCall("call_1", "search_channel_posts", "{\"query\":\"岛\"}")));
                    }
                    return new AiToolLoopService.ToolTurn("社区里有 1 条相关讨论 [1]", "", new AiUsage(10, 20),
                            List.of());
                });

        assertThat(requests).hasSize(2);
        assertThat(result.toolRounds()).isEqualTo(1);
        assertThat(result.content()).isEqualTo("社区里有 1 条相关讨论 [1]");
        assertThat(result.usage().completionTokens()).isEqualTo(20);
        assertThat(result.hits()).extracting(AiContextPost::getId).containsExactly("p1");
        // 第二轮请求必须包含 assistant tool_calls 与 tool 结果
        List<AiChatMessage> messages = requests.get(1).messages();
        assertThat(messages).anyMatch(AiChatMessage::hasToolCalls);
        assertThat(messages).anyMatch(AiChatMessage::isToolResult);
    }

    @Test
    void should_stop_after_two_tool_rounds_and_disable_tools() {
        when(registry.execute(anyString(), anyString(), any()))
                .thenReturn(new AiToolRegistry.ToolOutcome("{}", List.of(post("p1"))));

        AiToolLoopService.ToolLoopResult result = service.run(base(), definitions(), "ch-1",
                future(), () -> false, request -> {
                    requests.add(request);
                    return new AiToolLoopService.ToolTurn("", "", AiUsage.empty(),
                            List.of(new AiToolCall("call_" + requests.size(), "search_global_posts", "{}")));
                });

        // 第 1、2 轮带工具，第 3 轮强制不带工具后收敛
        assertThat(requests).hasSize(3);
        assertThat(requests.get(0).tools()).hasSize(1);
        assertThat(requests.get(1).tools()).hasSize(1);
        assertThat(requests.get(2).tools()).isEmpty();
        assertThat(result.toolRounds()).isEqualTo(2);
        verify(registry, org.mockito.Mockito.times(2)).execute(anyString(), anyString(), any());
    }

    @Test
    void should_not_execute_tools_when_user_cancelled() {
        AiToolLoopService.ToolLoopResult result = service.run(base(), definitions(), "ch-1",
                future(), () -> true, request -> {
                    requests.add(request);
                    return new AiToolLoopService.ToolTurn("", "", AiUsage.empty(),
                            List.of(new AiToolCall("call_1", "search_global_posts", "{}")));
                });

        assertThat(result.toolRounds()).isZero();
        assertThat(result.hits()).isEmpty();
        verify(registry, never()).execute(anyString(), anyString(), any());
    }

    @Test
    void should_fail_when_total_timeout_exhausted() {
        assertThatThrownBy(() -> service.run(base(), definitions(), null,
                System.currentTimeMillis() - 1, () -> false,
                request -> new AiToolLoopService.ToolTurn("", "", AiUsage.empty(), List.of())))
                .isInstanceOf(AiProviderException.class)
                .satisfies(ex -> assertThat(((AiProviderException) ex).getCode())
                        .isEqualTo(AiProviderException.TIMEOUT));
    }

    @Test
    void should_pass_remaining_timeout_to_each_round() {
        service.run(base(), definitions(), null, future(), () -> false, request -> {
            requests.add(request);
            return new AiToolLoopService.ToolTurn("ok", "", AiUsage.empty(), List.of());
        });

        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).timeoutSeconds()).isNotNull().isGreaterThan(0);
    }

    @Test
    void should_run_blocking_turn_via_provider() {
        AiProvider provider = mock(AiProvider.class);
        when(provider.chat(any())).thenReturn(new AiChatResult("阻塞回答", "思考", new AiUsage(3, 4), "m",
                List.of(), "stop"));

        AiToolLoopService.ToolTurn turn = service.blockingTurn(provider).run(base());

        assertThat(turn.content()).isEqualTo("阻塞回答");
        assertThat(turn.reasoning()).isEqualTo("思考");
        assertThat(turn.usage().promptTokens()).isEqualTo(3);
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private AiChatRequest base() {
        return AiChatRequest.of("m", List.of(AiChatMessage.user("岛上有露营攻略吗")), true, false, 512);
    }

    private List<AiToolDefinition> definitions() {
        return List.of(new AiToolDefinition("search_global_posts", "全站检索",
                java.util.Map.of("type", "object")));
    }

    private long future() {
        return System.currentTimeMillis() + 60_000;
    }

    private AiContextPost post(String id) {
        AiContextPost post = new AiContextPost();
        post.setId(id);
        post.setTitle("露营攻略");
        return post;
    }
}
