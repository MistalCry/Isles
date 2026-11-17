package com.isles.agent.search;

import com.isles.platform.setting.PlatformSettingService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 联网搜索日配额单元测试。
 */
class SearchQuotaTest {

    @Test
    void should_stop_when_daily_quota_exhausted() {
        PlatformSettingService settingService = mock(PlatformSettingService.class);
        when(settingService.getInt("agent.search.dailyQuota")).thenReturn(2);
        SearchQuota quota = new SearchQuota(settingService);

        assertThat(quota.tryConsume()).isTrue();
        assertThat(quota.tryConsume()).isTrue();
        // 第三次必须明确拒绝，而不是静默放过
        assertThat(quota.tryConsume()).isFalse();
        assertThat(quota.used()).isEqualTo(2);
        assertThat(quota.limit()).isEqualTo(2);
    }

    @Test
    void should_be_unlimited_when_quota_is_zero() {
        PlatformSettingService settingService = mock(PlatformSettingService.class);
        when(settingService.getInt("agent.search.dailyQuota")).thenReturn(0);
        SearchQuota quota = new SearchQuota(settingService);

        for (int i = 0; i < 5; i++) {
            assertThat(quota.tryConsume()).isTrue();
        }
        assertThat(quota.used()).isEqualTo(5);
    }

    // ------------------------------------------------------------------
    // 第 21 轮 P0-1：失败/空结果退还配额
    // ------------------------------------------------------------------

    @Test
    void should_refund_consumed_quota() {
        PlatformSettingService settingService = mock(PlatformSettingService.class);
        when(settingService.getInt("agent.search.dailyQuota")).thenReturn(2);
        SearchQuota quota = new SearchQuota(settingService);

        assertThat(quota.tryConsume()).isTrue();
        quota.refund();

        assertThat(quota.used()).isZero();
        // 退回来的名额能再用：两次都成功，第三次才拒绝
        assertThat(quota.tryConsume()).isTrue();
        assertThat(quota.tryConsume()).isTrue();
        assertThat(quota.tryConsume()).isFalse();
    }

    @Test
    void should_never_refund_below_zero() {
        PlatformSettingService settingService = mock(PlatformSettingService.class);
        when(settingService.getInt("agent.search.dailyQuota")).thenReturn(10);
        SearchQuota quota = new SearchQuota(settingService);

        // 没消费过也退（跨日归零后可能发生）：不能变成负数
        quota.refund();
        quota.refund();

        assertThat(quota.used()).isZero();
    }
}
