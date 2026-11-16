package com.isles.agent.service;

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
    private ChannelMapper channelMapper;
    private ChannelAccessService accessService;
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
        runtime = new AgentRuntime(profileMapper, runMapper, toolbox, planner, guard, memoryService,
                profileService, modelConfigService, channelMapper, mock(ChannelService.class),
                accessService, new JsonSupport(), mock(PlatformAuditService.class));
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

    private void stubHappyPath() {
        when(guard.enabled()).thenReturn(true);
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
