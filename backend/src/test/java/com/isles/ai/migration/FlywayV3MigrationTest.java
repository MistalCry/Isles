package com.isles.ai.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway V3 迁移脚本静态校验（不连接数据库）：确认 AI/平台治理表、users.platform_role 列、
 * 幂等唯一键与显式命名约束存在，避免交付时漏表或漏列。
 */
class FlywayV3MigrationTest {

    private static final List<String> V3_TABLES = List.of(
            "ai_model_configs", "ai_conversations", "ai_messages", "channel_ai_settings", "ai_task_logs");

    @Test
    void should_contain_all_v3_tables_and_platform_role_column() throws IOException {
        String sql;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("db/migration/V3__ai_and_platform.sql")) {
            assertThat(input).as("V3__ai_and_platform.sql 应存在于 classpath:db/migration").isNotNull();
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        for (String table : V3_TABLES) {
            assertThat(sql).contains("CREATE TABLE " + table + " (");
        }
        // 只允许给 users 追加 platform_role（不得改其它既有表结构）
        assertThat(sql).contains("ALTER TABLE users").contains("platform_role VARCHAR(16) NOT NULL DEFAULT 'USER'");
        assertThat(sql).contains("utf8mb4_0900_ai_ci").contains("ENGINE = InnoDB");
        assertThat(sql).contains("CONSTRAINT fk_");
        assertThat(sql).contains("UNIQUE KEY uk_");
        assertThat(sql).contains("KEY idx_");
        assertThat(sql).contains("DATETIME(3)");
        // 任务幂等键：同频道同任务同周期只执行一次
        assertThat(sql).contains("uk_ai_task_logs_channel_type_key (channel_id, task_type, frequency_key)");
        // 助手设置一个频道一条
        assertThat(sql).contains("uk_channel_ai_settings_channel (channel_id)");
    }
}
