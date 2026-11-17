package com.isles.agent.service;

import com.isles.agent.entity.AgentProfile;
import com.isles.agent.mapper.AgentRunMapper;
import com.isles.platform.setting.PlatformSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
        // 第 19 轮：间隔改为配置驱动，默认 120 / 10 分钟
        when(settingService.getInt("agent.minPostIntervalMinutes")).thenReturn(120);
        when(settingService.getInt("agent.minCommentIntervalMinutes")).thenReturn(10);
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
        when(runMapper.lastOutputAt("agent-1", "POST")).thenReturn(com.isles.common.time.ClockTimes.nowUtc());

        AgentGuard.GuardResult result = guard.checkPost(profile(5, 8), "标题", "内容");

        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("2 小时");
    }

    // ------------------------------------------------------------------
    // 第 19 轮 P0-1：间隔配置化（0 = 不限，改配置立即生效）
    // ------------------------------------------------------------------

    @Test
    void should_allow_consecutive_posts_when_interval_is_zero() {
        when(settingService.getInt("agent.minPostIntervalMinutes")).thenReturn(0);
        when(runMapper.countOutputSinceAll(eq("POST"), any())).thenReturn(0L);
        when(runMapper.countOutputSince(eq("agent-1"), eq("POST"), any())).thenReturn(0L);
        // 刚发过（上一秒）：0 = 不限，必须放行
        when(runMapper.lastOutputAt("agent-1", "POST")).thenReturn(com.isles.common.time.ClockTimes.nowUtc());
        when(memoryService.isDuplicateTopic(anyString(), anyString())).thenReturn(false);

        assertThat(guard.checkPost(profile(5, 8), "第一次", "正文").allowed()).isTrue();
        // 连续第二次（同一时刻再检查一遍）也要通过，否则联调没法连发
        assertThat(guard.checkPost(profile(5, 8), "第二次", "正文").allowed()).isTrue();
        // 间隔关闭后不该再去查上次时间（省一次查询）
        verify(runMapper, org.mockito.Mockito.never()).lastOutputAt(anyString(), anyString());
    }

    @Test
    void should_block_second_post_when_interval_is_60_minutes() {
        when(settingService.getInt("agent.minPostIntervalMinutes")).thenReturn(60);
        when(runMapper.countOutputSinceAll(eq("POST"), any())).thenReturn(0L);
        when(runMapper.countOutputSince(eq("agent-1"), eq("POST"), any())).thenReturn(0L);
        when(memoryService.isDuplicateTopic(anyString(), anyString())).thenReturn(false);
        // 30 分钟前发过：60 分钟间隔内 → 拦
        when(runMapper.lastOutputAt("agent-1", "POST"))
                .thenReturn(com.isles.common.time.ClockTimes.nowUtc().minusMinutes(30));

        AgentGuard.GuardResult blocked = guard.checkPost(profile(5, 8), "标题", "正文");
        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.reason()).contains("1 小时");

        // 90 分钟前发过：已过间隔 → 放行
        when(runMapper.lastOutputAt("agent-1", "POST"))
                .thenReturn(com.isles.common.time.ClockTimes.nowUtc().minusMinutes(90));
        assertThat(guard.checkPost(profile(5, 8), "标题", "正文").allowed()).isTrue();
    }

    @Test
    void should_apply_interval_change_without_restart() {
        when(runMapper.countOutputSinceAll(eq("POST"), any())).thenReturn(0L);
        when(runMapper.countOutputSince(eq("agent-1"), eq("POST"), any())).thenReturn(0L);
        when(memoryService.isDuplicateTopic(anyString(), anyString())).thenReturn(false);
        when(runMapper.lastOutputAt("agent-1", "POST"))
                .thenReturn(com.isles.common.time.ClockTimes.nowUtc().minusMinutes(5));

        // 同一个 guard 实例：先 0（不限）→ 放行
        when(settingService.getInt("agent.minPostIntervalMinutes")).thenReturn(0);
        assertThat(guard.checkPost(profile(5, 8), "标题", "正文").allowed()).isTrue();

        // 后台改成 120（配置服务写入即失效快照，无需重启）→ 立刻拦
        when(settingService.getInt("agent.minPostIntervalMinutes")).thenReturn(120);
        AgentGuard.GuardResult blocked = guard.checkPost(profile(5, 8), "标题", "正文");
        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.reason()).contains("2 小时");

        // 再改回 0 → 又放行
        when(settingService.getInt("agent.minPostIntervalMinutes")).thenReturn(0);
        assertThat(guard.checkPost(profile(5, 8), "标题", "正文").allowed()).isTrue();
    }

    @Test
    void should_apply_comment_interval_from_setting() {
        when(runMapper.countOutputSince(eq("agent-1"), eq("COMMENT"), any())).thenReturn(0L);
        when(runMapper.lastOutputAt("agent-1", "COMMENT"))
                .thenReturn(com.isles.common.time.ClockTimes.nowUtc().minusMinutes(2));

        // 默认 10 分钟：2 分钟前评论过 → 拦
        AgentGuard.GuardResult blocked = guard.checkComment(profile(5, 8));
        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.reason()).contains("10 分钟");

        // 改成 0 → 不限，连续评论都通过
        when(settingService.getInt("agent.minCommentIntervalMinutes")).thenReturn(0);
        assertThat(guard.checkComment(profile(5, 8)).allowed()).isTrue();
        assertThat(guard.checkComment(profile(5, 8)).allowed()).isTrue();
    }

    @Test
    void should_format_interval_for_prompt_text() {
        assertThat(AgentGuard.humanInterval(Duration.ofMinutes(120))).isEqualTo("2 小时");
        assertThat(AgentGuard.humanInterval(Duration.ofMinutes(60))).isEqualTo("1 小时");
        assertThat(AgentGuard.humanInterval(Duration.ofMinutes(10))).isEqualTo("10 分钟");
        assertThat(AgentGuard.humanInterval(Duration.ofMinutes(90))).isEqualTo("1 小时 30 分钟");
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
