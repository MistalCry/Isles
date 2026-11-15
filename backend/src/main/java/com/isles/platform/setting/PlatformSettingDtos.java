package com.isles.platform.setting;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 平台配置接口模型（docs/11 §5.2）。
 *
 * <p>后台列表返回「元数据 + 当前值」，前端按 {@code type} 渲染控件，不再硬编码每个配置项，
 * 新增配置项时前端无需改动。
 */
public final class PlatformSettingDtos {

    private PlatformSettingDtos() {
    }

    /**
     * 单个配置项。
     *
     * @param overridden 是否被后台覆盖（false 表示当前用的是代码默认值）
     */
    public record SettingItem(
            String key,
            String group,
            String label,
            String type,
            String value,
            String defaultValue,
            boolean overridden,
            boolean publicVisible,
            String description,
            Integer minValue,
            Integer maxValue,
            List<String> allowedValues) {
    }

    public record SettingGroupView(String group, String label, List<SettingItem> items) {
    }

    public record SettingListView(List<SettingGroupView> groups) {
    }

    public record UpdateSettingRequest(
            @NotNull(message = "配置值不能为空")
            @Size(max = 2000, message = "配置值最长 2000 个字符")
            String value) {
    }

    public record BatchItem(
            @NotBlank(message = "配置键不能为空") String key,
            @NotNull(message = "配置值不能为空")
            @Size(max = 2000, message = "配置值最长 2000 个字符") String value) {
    }

    public record BatchUpdateRequest(
            @NotEmpty(message = "至少提交一个配置项")
            @Size(max = 50, message = "单次最多提交 50 个配置项")
            List<@Valid BatchItem> items) {
    }

    public record HistoryItem(
            String id,
            String key,
            String oldValue,
            String newValue,
            String actorId,
            String actorName,
            String ip,
            Instant createdAt) {
    }

    public record HistoryPage(List<HistoryItem> items, long total, int page, int size) {
    }

    /**
     * 公开配置：只包含 {@code publicVisible=true} 的项，键值对直出，前端按 key 读取。
     */
    public record PublicConfigResponse(java.util.Map<String, String> values) {
    }
}
