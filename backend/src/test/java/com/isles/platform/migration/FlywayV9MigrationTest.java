package com.isles.platform.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway V9 迁移脚本静态校验（不连接数据库）：
 * 任务日志记录模型配置与模型快照、外键 ON DELETE SET NULL、索引命名。
 */
class FlywayV9MigrationTest {

    @Test
    void should_add_model_columns_to_task_logs() throws IOException {
        String sql = read("db/migration/V9__ai_task_log_model.sql");

        assertThat(sql).contains("ALTER TABLE ai_task_logs")
                .contains("ADD COLUMN model_config_id CHAR(26)     NULL")
                .contains("ADD COLUMN model           VARCHAR(128) NULL")
                .contains("AFTER error_message")
                .contains("AFTER model_config_id")
                .contains("ADD KEY idx_ai_task_logs_model_config (model_config_id)")
                .contains("CONSTRAINT fk_ai_task_logs_model_config")
                .contains("REFERENCES ai_model_configs (id)")
                // 模型配置被删除时历史日志仍保留 model 快照
                .contains("ON DELETE SET NULL");
    }

    private String read(String classpathResource) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(classpathResource)) {
            assertThat(input).as("%s 应存在于 classpath", classpathResource).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
