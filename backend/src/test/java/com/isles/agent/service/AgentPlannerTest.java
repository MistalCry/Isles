package com.isles.agent.service;

import com.isles.agent.entity.AgentProfile;
import com.isles.ai.entity.AiModelConfig;
import com.isles.ai.provider.AiChatResult;
import com.isles.ai.provider.AiProvider;
import com.isles.ai.provider.AiUsage;
import com.isles.ai.service.AiModelConfigService;
import com.isles.common.error.AppException;
import com.isles.common.json.JsonSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 计划器单元测试（两阶段 tick）：研究动作解析、只读白名单过滤、产出动作解析。
 */
class AgentPlannerTest {

    private AiProvider provider;
    private AgentPlanner planner;
    private AiModelConfig model;

    @BeforeEach
    void setUp() {
        AiModelConfigService modelConfigService = mock(AiModelConfigService.class);
        provider = mock(AiProvider.class);
        model = new AiModelConfig();
        model.setId("cfg-1");
        model.setModel("mock-chat");
        when(modelConfigService.providerFor(any())).thenReturn(provider);
        planner = new AgentPlanner(modelConfigService, new JsonSupport());
    }

    @Test
    void should_parse_multiple_research_actions() {
        reply("""
                {"actions":[
                  {"action":"web_search","args":{"query":"minecraft 建筑"}},
                  {"action":"list_channel_posts","args":{"channelId":"ch-1"}}
                ]}
                """);

        List<AgentPlanner.AgentPlan> plans = planner.research(model, profile(), List.of(), List.of());

        assertThat(plans).hasSize(2);
        assertThat(plans.get(0).action()).isEqualTo("web_search");
        assertThat(plans.get(0).args()).containsEntry("query", "minecraft 建筑");
        assertThat(plans.get(1).action()).isEqualTo("list_channel_posts");
    }

    @Test
    void should_filter_non_readonly_actions_in_research() {
        reply("""
                {"actions":[
                  {"action":"create_post","args":{"content":"直接发帖"}},
                  {"action":"web_search","args":{"query":"红石"}}
                ]}
                """);

        List<AgentPlanner.AgentPlan> plans = planner.research(model, profile(), List.of(), List.of());

        // 研究阶段只允许只读动作，写动作被丢弃
        assertThat(plans).hasSize(1);
        assertThat(plans.get(0).action()).isEqualTo("web_search");
    }

    @Test
    void should_cap_research_actions_at_two() {
        reply("""
                {"actions":[
                  {"action":"web_search","args":{"query":"a"}},
                  {"action":"web_search","args":{"query":"b"}},
                  {"action":"web_search","args":{"query":"c"}}
                ]}
                """);

        assertThat(planner.research(model, profile(), List.of(), List.of())).hasSize(2);
    }

    @Test
    void should_return_empty_when_model_declines_research() {
        reply("{\"actions\":[]}");

        assertThat(planner.research(model, profile(), List.of(), List.of())).isEmpty();
    }

    @Test
    void should_tolerate_code_fence_and_prose() {
        reply("""
                好的，我先查资料：
                ```json
                {"action":"web_search","args":{"query":"露营"}}
                ```
                上面就是计划。
                """);

        List<AgentPlanner.AgentPlan> plans = planner.research(model, profile(), List.of(), List.of());

        assertThat(plans).hasSize(1);
        assertThat(plans.get(0).args()).containsEntry("query", "露营");
    }

    @Test
    void should_parse_compose_action() {
        reply("{\"action\":\"comment\",\"args\":{\"postId\":\"post-1\",\"content\":\"同意\"}}");

        AgentPlanner.AgentPlan plan = planner.compose(model, profile(), List.of(), List.of(),
                "- [google cse] 红石教程 https://a.example.com", null, List.of("post-1"));

        assertThat(plan.action()).isEqualTo("comment");
        assertThat(plan.args()).containsEntry("postId", "post-1");
        assertThat(plan.tokensIn()).isPositive();
    }

    @Test
    void should_reject_non_json_compose_output() {
        reply("我觉得今天适合发个帖子。");

        assertThatThrownBy(() -> planner.compose(model, profile(), List.of(), List.of(),
                "素材", null, List.of()))
                .isInstanceOf(AppException.class);
    }

    private void reply(String content) {
        when(provider.chat(any())).thenReturn(new AiChatResult(content, null, new AiUsage(120, 40), "mock-chat"));
    }

    private AgentProfile profile() {
        AgentProfile profile = new AgentProfile();
        profile.setUserId("agent-1");
        profile.setDisplayName("岛民小蓝");
        profile.setTone("casual");
        profile.setInterests("[\"minecraft\",\"建筑\"]");
        return profile;
    }
}
