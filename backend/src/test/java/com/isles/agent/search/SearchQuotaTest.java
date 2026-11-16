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
}
