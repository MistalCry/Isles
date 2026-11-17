package com.isles.agent.service;

import com.isles.agent.dto.AgentDtos;
import com.isles.agent.entity.AgentProfile;
import com.isles.agent.entity.AgentRun;
import com.isles.agent.mapper.AgentProfileMapper;
import com.isles.agent.mapper.AgentRunMapper;
import com.isles.agent.tool.AgentToolbox;
import com.isles.ai.entity.AiModelConfig;
import com.isles.ai.service.AiModelConfigService;
import com.isles.channel.mapper.ChannelMapper;
import com.isles.channel.service.ChannelAccessService;
import com.isles.channel.service.ChannelService;
import com.isles.common.json.JsonSupport;
import com.isles.platform.audit.PlatformAuditService;
import com.isles.platform.setting.PlatformSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 代理运行时单元测试（V13）：kill switch、无模型降级、动作白名单、闸门拦截。
 */
class AgentRuntimeTest {

    private AgentProfileMapper profileMapper;
    private AgentRunMapper runMapper;
    private AgentToolbox toolbox;
    private AgentPlanner planner;
    private AgentGuard guard;
    private AgentMemoryService memoryService;
    private AgentProfileService profileService;
    private AiModelConfigService modelConfigService;
    private AgentModelService agentModelService;
    private ChannelMapper channelMapper;
    private ChannelAccessService accessService;
    private PlatformSettingService settingService;
    private AgentRuntime runtime;

    @BeforeEach
    void setUp() {
        profileMapper = mock(AgentProfileMapper.class);
        runMapper = mock(AgentRunMapper.class);
        toolbox = mock(AgentToolbox.class);
        planner = mock(AgentPlanner.class);
        guard = mock(AgentGuard.class);
        memoryService = mock(AgentMemoryService.class);
        profileService = mock(AgentProfileService.class);
        modelConfigService = mock(AiModelConfigService.class);
        channelMapper = mock(ChannelMapper.class);
        accessService = mock(ChannelAccessService.class);
        settingService = mock(PlatformSettingService.class);
        // 默认走单轮（旧行为），两阶段用例单独打桩
        when(settingService.getBoolean("agent.twoPhaseEnabled")).thenReturn(false);
        when(settingService.getBoolean("agent.researchFirst")).thenReturn(true);
        // 用真实的解析器 + mock 的模型服务：优先级的逻辑要真跑，不能 mock 掉
        agentModelService = new AgentModelService(modelConfigService);
        runtime = new AgentRuntime(profileMapper, runMapper, toolbox, planner, guard, memoryService,
                profileService, agentModelService, channelMapper, mock(ChannelService.class),
                accessService, new JsonSupport(), mock(PlatformAuditService.class), settingService);
        when(runMapper.insert(any(AgentRun.class))).thenReturn(1);
    }

    @Test
    void tick_should_do_nothing_when_kill_switch_off() {
        when(guard.enabled()).thenReturn(false);

        AgentRuntime.TickSummary summary = runtime.tick();

        assertThat(summary.killSwitchOff()).isTrue();
        assertThat(summary.selected()).isZero();
        verify(profileMapper, never()).selectActive();
        verify(runMapper, never()).insert(any(AgentRun.class));
    }

    @Test
    void should_record_skipped_when_no_model_available() {
        when(guard.enabled()).thenReturn(true);
        when(guard.checkRun()).thenReturn(AgentGuard.GuardResult.ok());
        when(modelConfigService.resolveDefault()).thenReturn(null);

        AgentRun run = runtime.runOne(profile(), true);

        assertThat(run.getStatus()).isEqualTo(AgentRun.Status.SKIPPED.name());
        assertThat(run.getError()).contains("未配置可用的 AI 模型");
        // 降级不能抛异常，也不能调模型
        verify(planner, never()).plan(any(), any(), any(), any(), any());
    }

    @Test
    void should_mark_guarded_when_action_not_in_whitelist() {
        stubHappyPath();
        when(planner.plan(any(), any(), any(), any(), any()))
                .thenReturn(new AgentPlanner.AgentPlan("send_dm", Map.of("to", "user-1"), "{}", 10, 5));

        AgentRun run = runtime.runOne(profile(), true);

        assertThat(run.getStatus()).isEqualTo(AgentRun.Status.GUARDED.name());
        assertThat(run.getError()).contains("白名单");
        verify(toolbox, never()).execute(anyString(), any(), any());
    }

    @Test
    void should_mark_guarded_when_post_limit_blocks() {
        stubHappyPath();
        when(planner.plan(any(), any(), any(), any(), any()))
                .thenReturn(new AgentPlanner.AgentPlan("create_post",
                        Map.of("channelId", "ch-1", "content", "正文", "title", "标题"), "{}", 10, 5));
        when(guard.checkPost(any(), anyString(), anyString()))
                .thenReturn(AgentGuard.GuardResult.blocked("该代理今日发帖已达上限（2/2）"));

        AgentRun run = runtime.runOne(profile(), true);

        assertThat(run.getStatus()).isEqualTo(AgentRun.Status.GUARDED.name());
        assertThat(run.getError()).contains("今日发帖");
        verify(toolbox, never()).execute(anyString(), any(), any());
    }

    @Test
    void should_execute_action_and_record_output() {
        stubHappyPath();
        when(planner.plan(any(), any(), any(), any(), any()))
                .thenReturn(new AgentPlanner.AgentPlan("like",
                        Map.of("targetType", "POST", "targetId", "post-1"), "{}", 12, 6));
        when(toolbox.execute(eq("like"), any(), any()))
                .thenReturn(new AgentToolbox.ToolOutcome("LIKE", "post-1", "已点赞帖子 post-1"));

        AgentRun run = runtime.runOne(profile(), true);

        assertThat(run.getStatus()).isEqualTo(AgentRun.Status.SUCCESS.name());
        assertThat(run.getOutputType()).isEqualTo("LIKE");
        assertThat(run.getOutputId()).isEqualTo("post-1");
        assertThat(run.getTokensIn()).isEqualTo(12);
        assertThat(run.getPlanJson()).isEqualTo("{}");
        verify(profileService).touchLastRun("agent-1");
    }

    @Test
    void should_record_guarded_when_tool_rejects_with_forbidden() {
        stubHappyPath();
        when(planner.plan(any(), any(), any(), any(), any()))
                .thenReturn(new AgentPlanner.AgentPlan("create_post", Map.of("content", "正文"), "{}", 1, 1));
        when(toolbox.execute(anyString(), any(), any()))
                .thenThrow(new com.isles.common.error.AppException(
                        com.isles.common.error.ErrorCode.SENSITIVE_WORD_BLOCKED));

        AgentRun run = runtime.runOne(profile(), true);

        assertThat(run.getStatus()).isEqualTo(AgentRun.Status.GUARDED.name());
    }

    @Test
    void should_respect_active_hours_window() {
        assertThat(runtime.inActiveHours(null, 12)).isTrue();
        assertThat(runtime.inActiveHours("[[20,24],[0,1]]", 21)).isTrue();
        assertThat(runtime.inActiveHours("[[20,24],[0,1]]", 0)).isTrue();
        assertThat(runtime.inActiveHours("[[20,24],[0,1]]", 12)).isFalse();
    }

    @Test
    void should_not_wake_paused_agent() {
        AgentProfile paused = profile();
        paused.setStatus("PAUSED");

        assertThat(runtime.shouldWake(paused, java.time.LocalDateTime.now())).isFalse();
    }


    // ------------------------------------------------------------------
    // D1 / D2 回归锁（第 16 轮补丁）
    // ------------------------------------------------------------------

    @Test
    void should_not_run_paused_agent_via_run_now() {
        AgentProfile paused = profile();
        paused.setStatus("PAUSED");
        when(profileService.requireProfile("agent-1")).thenReturn(paused);
        when(guard.enabled()).thenReturn(true);

        AgentDtos.RunNowResponse response = runtime.runNow("agent-1", "admin-1");

        assertThat(response.status()).isEqualTo(AgentRun.Status.SKIPPED.name());
        assertThat(response.reason()).contains("已暂停");
        assertThat(response.run()).isNull();
        // 与 kill switch 同语义：不执行、不写运行日志
        verify(runMapper, never()).insert(any(AgentRun.class));
        verify(planner, never()).plan(any(), any(), any(), any(), any());
    }

    @Test
    void should_record_skipped_when_model_resolution_throws() {
        when(guard.enabled()).thenReturn(true);
        when(guard.checkRun()).thenReturn(AgentGuard.GuardResult.ok());
        // 零启用模型时 resolveDefault 直接抛业务异常（AiModelConfigService:127-140）
        when(modelConfigService.resolveDefault()).thenThrow(new com.isles.common.error.AppException(
                com.isles.common.error.ErrorCode.BUSINESS_RULE_VIOLATION, "尚未配置可用的 AI 模型"));

        AgentRun run = runtime.runOne(profile(), true);

        assertThat(run.getStatus()).isEqualTo(AgentRun.Status.SKIPPED.name());
        assertThat(run.getError()).contains("未配置可用的 AI 模型");
        verify(planner, never()).plan(any(), any(), any(), any(), any());
    }


    // ------------------------------------------------------------------
    // 两阶段 tick（P0-1）
    // ------------------------------------------------------------------

    @Test
    void should_research_first_then_compose_and_sum_tokens() {
        stubHappyPath();
        when(settingService.getBoolean("agent.twoPhaseEnabled")).thenReturn(true);
        AgentPlanner.AgentPlan research = new AgentPlanner.AgentPlan("web_search",
                Map.of("query", "minecraft 建筑"), "{\"action\":\"web_search\"}", 10, 5);
        when(planner.research(any(), any(), any(), any())).thenReturn(List.of(research));
        when(toolbox.execute(eq("web_search"), any(), any())).thenReturn(new AgentToolbox.ToolOutcome(
                "NONE", null, "联网搜索「minecraft 建筑」（1 条）",
                Map.of("query", "minecraft 建筑", "count", 1,
                        "results", List.of(Map.of("title", "建筑教程", "url", "https://wiki.example.com/build",
                                "engine", "google cse", "snippet", "从地基开始规划")))));
        when(planner.compose(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AgentPlanner.AgentPlan("create_post",
                        Map.of("channelId", "ch-1", "title", "建筑心得", "content", "从地基开始规划"), "{}", 20, 8));
        when(toolbox.execute(eq("create_post"), any(), any()))
                .thenReturn(new AgentToolbox.ToolOutcome("POST", "post-9", "已发帖《建筑心得》"));

        AgentRun run = runtime.runOne(profile(), true);

        assertThat(run.getStatus()).isEqualTo(AgentRun.Status.SUCCESS.name());
        assertThat(run.getOutputType()).isEqualTo("POST");
        // 两阶段 token 累加：10+20 / 5+8
        assertThat(run.getTokensIn()).isEqualTo(30);
        assertThat(run.getTokensOut()).isEqualTo(13);
        assertThat(run.getPlanJson()).contains("research").contains("compose");
        verify(planner).research(any(), any(), any(), any());
    }

    @Test
    void should_pass_research_materials_into_compose_prompt() {
        stubHappyPath();
        when(settingService.getBoolean("agent.twoPhaseEnabled")).thenReturn(true);
        when(planner.research(any(), any(), any(), any())).thenReturn(List.of(
                new AgentPlanner.AgentPlan("web_search", Map.of("query", "红石"), "{}", 1, 1)));
        when(toolbox.execute(eq("web_search"), any(), any())).thenReturn(new AgentToolbox.ToolOutcome(
                "NONE", null, "联网搜索", Map.of("count", 1, "results", List.of(
                Map.of("title", "红石教程", "url", "https://wiki.example.com/redstone",
                        "engine", "google cse", "snippet", "红石用于传输信号")))));
        when(planner.compose(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AgentPlanner.AgentPlan("none", Map.of(), "{}", 1, 1));

        runtime.runOne(profile(), true);

        org.mockito.ArgumentCaptor<String> materials = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(planner).compose(any(), any(), any(), any(), materials.capture(), any(), any());
        // 素材必须带 url 与摘要，否则产出阶段只能编
        assertThat(materials.getValue()).contains("https://wiki.example.com/redstone")
                .contains("红石用于传输信号");
    }

    @Test
    void should_reject_fabricated_post_id() {
        stubHappyPath();
        when(settingService.getBoolean("agent.twoPhaseEnabled")).thenReturn(true);
        when(planner.research(any(), any(), any(), any())).thenReturn(List.of(
                new AgentPlanner.AgentPlan("list_channel_posts", Map.of("channelId", "ch-1"), "{}", 1, 1)));
        when(toolbox.execute(eq("list_channel_posts"), any(), any())).thenReturn(new AgentToolbox.ToolOutcome(
                "NONE", null, "频道最新帖", Map.of("postIds", List.of("post-1"))));
        // 模型凭空拼了一个 id（用户实测：频道ID_2024-06-15_14:30:00）
        when(planner.compose(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AgentPlanner.AgentPlan("comment",
                        Map.of("postId", "ch-1_2024-06-15_14:30:00", "content", "说点什么"), "{}", 1, 1));

        AgentRun run = runtime.runOne(profile(), true);

        assertThat(run.getStatus()).isEqualTo(AgentRun.Status.GUARDED.name());
        assertThat(run.getError()).contains("不在本轮可见列表");
        verify(toolbox, never()).execute(eq("comment"), any(), any());
    }

    @Test
    void should_allow_comment_when_target_was_seen_this_tick() {
        stubHappyPath();
        when(settingService.getBoolean("agent.twoPhaseEnabled")).thenReturn(true);
        when(planner.research(any(), any(), any(), any())).thenReturn(List.of(
                new AgentPlanner.AgentPlan("search_site_posts", Map.of("keyword", "红石"), "{}", 1, 1)));
        when(toolbox.execute(eq("search_site_posts"), any(), any())).thenReturn(new AgentToolbox.ToolOutcome(
                "NONE", null, "站内检索结果", Map.of("postIds", List.of("post-1", "post-2"))));
        when(planner.compose(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AgentPlanner.AgentPlan("comment",
                        Map.of("postId", "post-2", "content", "这个思路可以"), "{}", 1, 1));
        when(toolbox.execute(eq("comment"), any(), any()))
                .thenReturn(new AgentToolbox.ToolOutcome("COMMENT", "comment-1", "已评论帖子 post-2"));

        AgentRun run = runtime.runOne(profile(), true);

        assertThat(run.getStatus()).isEqualTo(AgentRun.Status.SUCCESS.name());
        assertThat(run.getOutputType()).isEqualTo("COMMENT");
        assertThat(run.getOutputId()).isEqualTo("comment-1");
    }

    @Test
    void should_fallback_to_persona_keywords_when_research_is_empty() {
        stubHappyPath();
        when(settingService.getBoolean("agent.twoPhaseEnabled")).thenReturn(true);
        AgentProfile profile = profile();
        profile.setInterests("[\"minecraft\",\"建筑\"]");
        when(planner.research(any(), any(), any(), any())).thenReturn(List.of());
        when(toolbox.execute(eq("web_search"), any(), any())).thenReturn(new AgentToolbox.ToolOutcome(
                "NONE", null, "联网搜索", Map.of("count", 0, "results", List.of())));
        when(planner.compose(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AgentPlanner.AgentPlan("none", Map.of(), "{}", 1, 1));

        runtime.runOne(profile, true);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> args =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(toolbox).execute(eq("web_search"), args.capture(), any());
        // 人设兴趣词兜底：不交给模型决定「要不要研究」
        assertThat(String.valueOf(args.getValue().get("query"))).contains("minecraft").contains("建筑");
    }

    @Test
    void should_skip_research_when_research_first_disabled() {
        stubHappyPath();
        when(settingService.getBoolean("agent.twoPhaseEnabled")).thenReturn(true);
        when(settingService.getBoolean("agent.researchFirst")).thenReturn(false);
        when(planner.compose(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AgentPlanner.AgentPlan("none", Map.of(), "{}", 1, 1));

        AgentRun run = runtime.runOne(profile(), true);

        assertThat(run.getStatus()).isEqualTo(AgentRun.Status.SUCCESS.name());
        verify(planner, never()).research(any(), any(), any(), any());
    }

    @Test
    void should_fall_back_to_single_phase_when_two_phase_disabled() {
        stubHappyPath();
        when(planner.plan(any(), any(), any(), any(), any()))
                .thenReturn(new AgentPlanner.AgentPlan("none", Map.of(), "{}", 1, 1));

        runtime.runOne(profile(), true);

        verify(planner).plan(any(), any(), any(), any(), any());
        verify(planner, never()).research(any(), any(), any(), any());
        verify(planner, never()).compose(any(), any(), any(), any(), any(), any(), any());
    }

    // ------------------------------------------------------------------
    // 第 19 轮 P0-2：每个代理可选模型
    // ------------------------------------------------------------------

    @Test
    void should_use_agent_specific_model() {
        stubHappyPath();
        AiModelConfig specific = model("cfg-2", "model-b");
        when(modelConfigService.findEnabled("cfg-2")).thenReturn(specific);
        AgentProfile profile = profile();
        profile.setModelConfigId("cfg-2");
        when(planner.plan(any(), any(), any(), any(), any()))
                .thenReturn(new AgentPlanner.AgentPlan("none", Map.of(), "{}", 1, 1));

        AgentRun run = runtime.runOne(profile, true);

        ArgumentCaptor<AiModelConfig> captor = ArgumentCaptor.forClass(AiModelConfig.class);
        verify(planner).plan(captor.capture(), any(), any(), any(), any());
        // 用的必须是这个代理指定的模型，不是平台默认
        assertThat(captor.getValue().getId()).isEqualTo("cfg-2");
        assertThat(run.getModelConfigId()).isEqualTo("cfg-2");
        assertThat(run.getModel()).isEqualTo("model-b");
        assertThat(run.getError()).isNull();
    }

    @Test
    void should_fall_back_to_default_and_note_it_when_agent_model_disabled() {
        stubHappyPath();
        AgentProfile profile = profile();
        profile.setModelConfigId("cfg-deleted");
        // 指定模型已被删除/禁用：findEnabled 返回 null（AiModelConfigService:74-80）
        when(modelConfigService.findEnabled("cfg-deleted")).thenReturn(null);
        when(planner.plan(any(), any(), any(), any(), any()))
                .thenReturn(new AgentPlanner.AgentPlan("none", Map.of(), "{}", 1, 1));

        AgentRun run = runtime.runOne(profile, true);

        // 不失败：回落到平台默认，并在 error 里留下可追溯的说明
        assertThat(run.getStatus()).isEqualTo(AgentRun.Status.SUCCESS.name());
        assertThat(run.getModel()).isEqualTo("mock-chat");
        assertThat(run.getError()).contains("指定模型不可用，已回落默认");
    }

    @Test
    void should_record_actual_model_for_both_phases() {
        stubHappyPath();
        AiModelConfig specific = model("cfg-2", "model-b");
        when(modelConfigService.findEnabled("cfg-2")).thenReturn(specific);
        when(settingService.getBoolean("agent.twoPhaseEnabled")).thenReturn(true);
        AgentProfile profile = profile();
        profile.setModelConfigId("cfg-2");
        when(planner.research(any(), any(), any(), any())).thenReturn(List.of(
                new AgentPlanner.AgentPlan("list_channel_posts", Map.of("channelId", "ch-1"), "{}", 5, 2)));
        when(toolbox.execute(eq("list_channel_posts"), any(), any())).thenReturn(new AgentToolbox.ToolOutcome(
                "NONE", null, "频道最新帖", Map.of("postIds", List.of("post-1"))));
        when(planner.compose(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AgentPlanner.AgentPlan("none", Map.of(), "{}", 3, 1));

        AgentRun run = runtime.runOne(profile, true);

        ArgumentCaptor<AiModelConfig> researchModel = ArgumentCaptor.forClass(AiModelConfig.class);
        ArgumentCaptor<AiModelConfig> composeModel = ArgumentCaptor.forClass(AiModelConfig.class);
        verify(planner).research(researchModel.capture(), any(), any(), any());
        verify(planner).compose(composeModel.capture(), any(), any(), any(), any(), any(), any());
        // 两阶段两次调用共用同一个模型：日志里记一次就是对账基准
        assertThat(researchModel.getValue().getId()).isEqualTo("cfg-2");
        assertThat(composeModel.getValue().getId()).isEqualTo("cfg-2");
        assertThat(run.getModel()).isEqualTo("model-b");
        assertThat(run.getTokensIn()).isEqualTo(8);
        assertThat(run.getTokensOut()).isEqualTo(3);
    }

    @Test
    void should_not_use_default_when_agent_model_is_usable() {
        stubHappyPath();
        when(modelConfigService.findEnabled("cfg-2")).thenReturn(model("cfg-2", "model-b"));
        AgentProfile profile = profile();
        profile.setModelConfigId("cfg-2");
        when(planner.plan(any(), any(), any(), any(), any()))
                .thenReturn(new AgentPlanner.AgentPlan("none", Map.of(), "{}", 1, 1));

        runtime.runOne(profile, true);

        // 指定模型可用时不该再去解析默认模型（避免默认模型异常影响该代理）
        verify(modelConfigService, never()).resolveDefault();
    }

    private AiModelConfig model(String id, String name) {
        AiModelConfig config = new AiModelConfig();
        config.setId(id);
        config.setName(name);
        config.setModel(name);
        config.setEnabled(true);
        return config;
    }

    private void stubHappyPath() {        when(guard.enabled()).thenReturn(true);
        when(guard.checkRun()).thenReturn(AgentGuard.GuardResult.ok());
        when(guard.checkPost(any(), any(), any())).thenReturn(AgentGuard.GuardResult.ok());
        when(guard.checkComment(any())).thenReturn(AgentGuard.GuardResult.ok());
        AiModelConfig model = new AiModelConfig();
        model.setId("cfg-1");
        model.setModel("mock-chat");
        when(modelConfigService.resolveDefault()).thenReturn(model);
        when(channelMapper.selectJoinedChannels("agent-1")).thenReturn(List.of());
        when(channelMapper.selectRecommendationCandidates(eq("agent-1"), any(Integer.class))).thenReturn(List.of());
        when(memoryService.recentTopics(eq("agent-1"), any(Integer.class))).thenReturn(List.of());
        // none 是「不行动」，也要有返回值，否则 execute 返回 null 会 NPE
        when(toolbox.execute(eq("none"), any(), any()))
                .thenReturn(new AgentToolbox.ToolOutcome("NONE", null, "本次不行动"));
    }

    private AgentProfile profile() {
        AgentProfile profile = new AgentProfile();
        profile.setId("profile-1");
        profile.setUserId("agent-1");
        profile.setDisplayName("岛民小蓝");
        profile.setStatus("ACTIVE");
        profile.setPostsPerDay(2);
        profile.setCommentsPerDay(8);
        return profile;
    }
}
