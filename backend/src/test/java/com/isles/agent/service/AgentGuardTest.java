package com.isles.agent.service;

import com.isles.agent.entity.AgentProfile;
import com.isles.agent.mapper.AgentRunMapper;
import com.isles.platform.setting.PlatformSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 代理闸门单元测试（V13）：kill switch、token 预算、日上限、发帖间隔、话题去重。
 */
class AgentGuardTest {

    private PlatformSettingService settingService;
    private AgentRunMapper runMapper;
    private AgentMemoryService memoryService;
    private AgentGuard guard;

    @BeforeEach
    void setUp() {
        settingService = mock(PlatformSettingService.class);
        runMapper = mock(AgentRunMapper.class);
        memoryService = mock(AgentMemoryService.class);
        guard = new AgentGuard(settingService, runMapper, memoryService);
        when(settingService.getBoolean("agent.enabled")).thenReturn(true);
        when(settingService.getInt("agent.dailyTokenBudget")).thenReturn(100_000);
        when(settingService.getInt("agent.dailyPostLimit")).thenReturn(20);
    }

    @Test
    void should_block_everything_when_kill_switch_off() {
        when(settingService.getBoolean("agent.enabled")).thenReturn(false);

        assertThat(guard.enabled()).isFalse();
        assertThat(guard.checkRun().allowed()).isFalse();
        assertThat(guard.checkRun().reason()).contains("总开关");
    }

    @Test
    void should_block_when_token_budget_exhausted() {
        when(runMapper.sumTokensSince(any())).thenReturn(120_000L);

        AgentGuard.GuardResult result = guard.checkRun();

        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("token");
    }

    @Test
    void should_block_post_when_platform_daily_limit_reached() {
        when(runMapper.countOutputSinceAll(eq("POST"), any())).thenReturn(20L);

        AgentGuard.GuardResult result = guard.checkPost(profile(2, 8), "标题", "内容");

        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("全平台");
    }

    @Test
    void should_block_post_when_profile_daily_limit_reached() {
        when(runMapper.countOutputSinceAll(eq("POST"), any())).thenReturn(0L);
        when(runMapper.countOutputSince(eq("agent-1"), eq("POST"), any())).thenReturn(2L);

        AgentGuard.GuardResult result = guard.checkPost(profile(2, 8), "标题", "内容");

        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("今日发帖");
    }

    @Test
    void should_block_post_within_two_hour_interval() {
        when(runMapper.countOutputSinceAll(eq("POST"), any())).thenReturn(0L);
        when(runMapper.countOutputSince(eq("agent-1"), eq("POST"), any())).thenReturn(0L);
        when(runMapper.lastOutputAt("agent-1", "POST")).thenReturn(LocalDateTime.now());

        AgentGuard.GuardResult result = guard.checkPost(profile(5, 8), "标题", "内容");

        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("2 小时");
    }

    @Test
    void should_block_duplicate_topic() {
        when(runMapper.countOutputSinceAll(eq("POST"), any())).thenReturn(0L);
        when(runMapper.countOutputSince(eq("agent-1"), eq("POST"), any())).thenReturn(0L);
        when(runMapper.lastOutputAt("agent-1", "POST")).thenReturn(null);
        when(memoryService.isDuplicateTopic("agent-1", "露营装备清单")).thenReturn(true);

        AgentGuard.GuardResult result = guard.checkPost(profile(5, 8), "露营装备清单", "正文");

        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("重复");
    }

    @Test
    void should_allow_post_when_all_checks_pass() {
        when(runMapper.countOutputSinceAll(eq("POST"), any())).thenReturn(1L);
        when(runMapper.countOutputSince(eq("agent-1"), eq("POST"), any())).thenReturn(0L);
        when(runMapper.lastOutputAt("agent-1", "POST")).thenReturn(null);
        when(memoryService.isDuplicateTopic(anyString(), anyString())).thenReturn(false);

        assertThat(guard.checkPost(profile(5, 8), "新话题", "正文").allowed()).isTrue();
    }

    @Test
    void should_block_comment_when_daily_limit_reached() {
        when(runMapper.countOutputSince(eq("agent-1"), eq("COMMENT"), any())).thenReturn(8L);

        assertThat(guard.checkComment(profile(2, 8)).allowed()).isFalse();
    }

    private AgentProfile profile(int postsPerDay, int commentsPerDay) {
        AgentProfile profile = new AgentProfile();
        profile.setUserId("agent-1");
        profile.setPostsPerDay(postsPerDay);
        profile.setCommentsPerDay(commentsPerDay);
        profile.setStatus("ACTIVE");
        return profile;
    }
}
