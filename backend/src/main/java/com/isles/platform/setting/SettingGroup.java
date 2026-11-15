package com.isles.platform.setting;

import java.util.Arrays;

/**
 * 配置分组（后台左侧 Tab 顺序即枚举声明顺序）。
 */
public enum SettingGroup {

    SITE("site", "站点信息"),
    AUTH("auth", "注册与准入"),
    CONTENT("content", "内容策略"),
    INTERACTION("interaction", "互动与发现"),
    MEDIA("media", "上传与媒体"),
    AI("ai", "AI 能力"),
    OPS("ops", "运营开关");

    private final String key;
    private final String label;

    SettingGroup(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    /**
     * 未知分组回落 {@link #OPS}，避免新增定义时因拼写错误导致启动失败。
     */
    public static SettingGroup fromKey(String key) {
        return Arrays.stream(values())
                .filter(group -> group.key.equals(key))
                .findFirst()
                .orElse(OPS);
    }
}
