package com.isles.platform.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway V8 迁移脚本静态校验（不连接数据库）：
 * 频道助手模型外键、工具调用开关默认关闭、索引/约束命名。
 */
class FlywayV8MigrationTest {

    @Test
    void should_add_channel_model_and_tool_switch() throws IOException {
        String sql = read("db/migration/V8__channel_model_and_tool_calling.sql");

        // 工具调用默认关：老模型配置升级后行为不变
        assertThat(sql).contains("ALTER TABLE ai_model_configs")
                .contains("ADD COLUMN tool_call_enabled TINYINT(1) NOT NULL DEFAULT 0")
                .contains("AFTER thinking_mode");

        // 频道模型可空 + 外键 SET NULL（删模型不阻塞，运行期回落平台默认）
        assertThat(sql).contains("ALTER TABLE channel_ai_settings")
                .contains("ADD COLUMN model_config_id CHAR(26) NULL")
                .contains("AFTER persona")
                .contains("ADD KEY idx_channel_ai_settings_model_config (model_config_id)")
                .contains("CONSTRAINT fk_channel_ai_settings_model_config")
                .contains("REFERENCES ai_model_configs (id)")
                .contains("ON DELETE SET NULL");
    }

    private String read(String classpathResource) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(classpathResource)) {
            assertThat(input).as("%s 应存在于 classpath", classpathResource).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
