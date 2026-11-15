package com.isles.platform.setting;

import java.util.List;

/**
 * 单个配置项的定义（docs/11 §2.2）。
 *
 * <p>定义是**默认值与校验规则的唯一事实来源**：数据库只存覆盖值，新增配置项只需在
 * {@link SettingDefinitions} 增加一条定义，无需写数据库迁移。
 *
 * @param key           配置键，形如 {@code site.name}，全局唯一
 * @param group         分组键，见 {@link SettingGroup}
 * @param label         后台展示名
 * @param type          值类型
 * @param defaultValue  默认值（字符串形式，需能被 {@code type} 解析）
 * @param publicVisible 是否通过公开接口 {@code GET /platform/config} 暴露
 * @param description   说明文案（后台表单提示）
 * @param minValue      INT 为最小值；STRING 为最小长度；null 表示不限制
 * @param maxValue      INT 为最大值；STRING 为最大长度；null 表示不限制
 * @param allowedValues 非空时值必须命中其中之一（枚举型配置）
 */
public record SettingDefinition(
        String key,
        String group,
        String label,
        SettingType type,
        String defaultValue,
        boolean publicVisible,
        String description,
        Integer minValue,
        Integer maxValue,
        List<String> allowedValues) {

    public SettingDefinition {
        allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
    }
}
