package com.isles.platform.setting;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 配置注册表静态校验（docs/11 §2.2）：键唯一、默认值可解析、公开项不含敏感配置。
 */
class SettingDefinitionsTest {

    @Test
    void should_have_unique_keys() {
        Set<String> keys = new HashSet<>();
        for (SettingDefinition definition : SettingDefinitions.all()) {
            assertThat(keys.add(definition.key()))
                    .as("配置键重复: %s", definition.key())
                    .isTrue();
        }
    }

    @Test
    void should_have_parseable_default_values() {
        for (SettingDefinition definition : SettingDefinitions.all()) {
            switch (definition.type()) {
                case INT -> assertThatCode(() -> Integer.parseInt(definition.defaultValue()))
                        .as("默认值必须是整数: %s", definition.key())
                        .doesNotThrowAnyException();
                case BOOL -> assertThat(definition.defaultValue())
                        .as("默认值必须是布尔: %s", definition.key())
                        .isIn("true", "false");
                case STRING -> {
                    if (definition.minValue() != null) {
                        assertThat(definition.defaultValue().length())
                                .as("默认值不满足最小长度: %s", definition.key())
                                .isGreaterThanOrEqualTo(definition.minValue());
                    }
                    if (definition.maxValue() != null) {
                        assertThat(definition.defaultValue().length())
                                .as("默认值超出最大长度: %s", definition.key())
                                .isLessThanOrEqualTo(definition.maxValue());
                    }
                }
                case JSON -> assertThat(definition.defaultValue()).isNotBlank();
            }
        }
    }

    @Test
    void should_group_every_definition_into_known_group() {
        List<SettingGroup> groups = List.of(SettingGroup.values());
        for (SettingDefinition definition : SettingDefinitions.all()) {
            assertThat(groups.stream().map(SettingGroup::key).toList())
                    .as("未知分组: %s", definition.key())
                    .contains(definition.group());
        }
    }

    @Test
    void should_expose_only_safe_public_items() {
        List<String> publicKeys = SettingDefinitions.all().stream()
                .filter(SettingDefinition::publicVisible)
                .map(SettingDefinition::key)
                .toList();

        assertThat(publicKeys).contains("site.name", "auth.register.enabled");
        // 敏感/内部配置不得公开
        assertThat(publicKeys).doesNotContain("auth.password.minLength", "ai.qa.dailyQuota");
    }
}
