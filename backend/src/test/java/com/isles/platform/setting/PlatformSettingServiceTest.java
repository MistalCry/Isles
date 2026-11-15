package com.isles.platform.setting;

import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.json.JsonSupport;
import com.isles.platform.audit.PlatformAuditService;
import com.isles.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 配置中心单元测试（docs/11 §6.1）：默认值回落、覆盖值生效、类型校验、历史与审计、重置、公开子集。
 */
class PlatformSettingServiceTest {

    private PlatformSettingMapper settingMapper;
    private PlatformSettingHistoryMapper historyMapper;
    private PlatformAuditService auditService;
    private PlatformSettingService service;

    @BeforeEach
    void setUp() {
        settingMapper = mock(PlatformSettingMapper.class);
        historyMapper = mock(PlatformSettingHistoryMapper.class);
        auditService = mock(PlatformAuditService.class);
        when(settingMapper.selectList(any())).thenReturn(List.of());
        service = new PlatformSettingService(settingMapper, historyMapper, auditService,
                mock(UserMapper.class), new JsonSupport());
    }

    @Test
    void should_fallback_to_default_when_no_override() {
        assertThat(service.getString("site.name")).isEqualTo("群岛");
        assertThat(service.getInt("interaction.feed.pageSize")).isEqualTo(20);
        assertThat(service.getBoolean("auth.register.enabled")).isTrue();
    }

    @Test
    void should_apply_override_value() {
        when(settingMapper.selectList(any())).thenReturn(List.of(setting("site.name", "我的群岛")));

        assertThat(service.getString("site.name")).isEqualTo("我的群岛");
    }

    @Test
    void should_reject_unknown_key() {
        assertThatThrownBy(() -> service.update("nope.key", "x", "admin-1"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.SETTING_NOT_FOUND));
    }

    @Test
    void should_reject_non_integer_value() {
        assertThatThrownBy(() -> service.update("interaction.feed.pageSize", "abc", "admin-1"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.SETTING_VALIDATION_FAILED));
        verify(settingMapper, never()).insert(org.mockito.ArgumentMatchers.<PlatformSetting>any());
    }

    @Test
    void should_reject_out_of_range_value() {
        assertThatThrownBy(() -> service.update("interaction.feed.pageSize", "999", "admin-1"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.SETTING_VALIDATION_FAILED));
    }

    @Test
    void should_reject_empty_required_text() {
        assertThatThrownBy(() -> service.update("site.name", "   ", "admin-1"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.SETTING_VALIDATION_FAILED));
    }

    @Test
    void should_normalize_boolean_and_write_history_with_audit() {
        when(settingMapper.selectById("auth.register.enabled")).thenReturn(null);

        PlatformSettingDtos.SettingItem item = service.update("auth.register.enabled", "FALSE", "admin-1");

        assertThat(item.value()).isEqualTo("false");
        assertThat(item.overridden()).isTrue();
        verify(settingMapper).insert(any(PlatformSetting.class));
        verify(historyMapper).insert(any(PlatformSettingHistory.class));
        verify(auditService).record(eq("admin-1"), any(), eq("platform.config.update"), eq("CONFIG"),
                eq("auth.register.enabled"), any());
        // 写入后快照失效：再次读取会重新加载（此处模拟库中已有覆盖值）
        when(settingMapper.selectList(any()))
                .thenReturn(List.of(setting("auth.register.enabled", "false")));
        assertThat(service.getBoolean("auth.register.enabled")).isFalse();
    }

    @Test
    void should_reset_to_default_and_keep_history() {
        when(settingMapper.selectById("site.name")).thenReturn(setting("site.name", "我的群岛"));
        when(settingMapper.deleteByKey("site.name")).thenReturn(1);

        PlatformSettingDtos.SettingItem item = service.reset("site.name", "admin-1");

        assertThat(item.value()).isEqualTo("群岛");
        assertThat(item.overridden()).isFalse();
        verify(settingMapper).deleteByKey("site.name");
        verify(historyMapper).insert(any(PlatformSettingHistory.class));
        verify(auditService).record(eq("admin-1"), any(), eq("platform.config.reset"), eq("CONFIG"),
                eq("site.name"), any());
    }

    @Test
    void should_export_only_public_values() {
        Map<String, String> values = service.publicValues();

        assertThat(values).containsKey("site.name").containsKey("auth.register.enabled");
        assertThat(values).doesNotContainKeys("auth.password.minLength", "ai.qa.dailyQuota", "media.video.maxMb");
    }

    @Test
    void should_list_items_grouped_by_group() {
        PlatformSettingDtos.SettingListView view = service.list();

        assertThat(view.groups()).isNotEmpty();
        assertThat(view.groups().get(0).group()).isEqualTo(SettingGroup.SITE.key());
        assertThat(view.groups().stream().flatMap(group -> group.items().stream()).toList())
                .hasSize(SettingDefinitions.all().size());
    }

    private PlatformSetting setting(String key, String value) {
        PlatformSetting setting = new PlatformSetting();
        setting.setSettingKey(key);
        setting.setSettingValue(value);
        return setting;
    }
}
