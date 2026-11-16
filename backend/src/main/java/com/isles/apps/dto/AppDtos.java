package com.isles.apps.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 频道应用接口模型（V10 / docs/05 §2.14）。
 */
public final class AppDtos {

    private AppDtos() {
    }

    public record AppView(
            String id,
            String channelId,
            String appType,
            String name,
            String iconUrl,
            String description,
            int sortOrder,
            boolean enabled,
            Instant createdAt) {
    }

    public record SaveAppRequest(
            @NotBlank(message = "应用类型不能为空")
            @Pattern(regexp = "VOICE_ROOM|LIVE_ROOM|CUSTOM", message = "应用类型取值必须为 VOICE_ROOM、LIVE_ROOM 或 CUSTOM")
            String appType,

            @NotBlank(message = "应用名称不能为空")
            @Size(max = 32, message = "应用名称最长 32 个字符")
            String name,

            @Size(max = 500, message = "图标地址最长 500 个字符")
            String iconUrl,

            @Size(max = 200, message = "描述最长 200 个字符")
            String description,

            @Min(value = 0, message = "排序值不能为负")
            @Max(value = 999, message = "排序值最大 999")
            Integer sortOrder,

            Boolean enabled) {
    }

    /** 批量重排：按数组顺序写入 sort_order（0 起）。 */
    public record ReorderRequest(
            @NotEmpty(message = "至少提交一个应用 id")
            @Size(max = 20, message = "单次最多重排 20 个应用")
            List<String> appIds) {
    }
}
