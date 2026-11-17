package com.isles.agent.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway V14 迁移脚本静态校验（不连库）：每个代理可选模型 + 运行日志记实际模型。
 */
class FlywayV14MigrationTest {

    @Test
    void should_add_agent_model_and_run_model_columns() throws IOException {
        String sql = read("db/migration/V14__agent_model_and_run_model.sql");

        // 人设侧：NULL = 跟随平台默认
        assertThat(sql).contains("ALTER TABLE agent_profiles");
        assertThat(sql).contains("ADD COLUMN model_config_id CHAR(26) NULL");
        assertThat(sql).contains("COMMENT '代理使用的模型配置；NULL=跟随平台默认'");
        // 模型被删除时置空，人设不能因为删了一个模型就整行失败
        assertThat(sql).contains("fk_agent_profiles_model_config");
        assertThat(sql).contains("ON DELETE SET NULL ON UPDATE RESTRICT");

        // 运行日志侧：配置 id + 模型名快照（与 V9 的 ai_task_logs 同构）
        assertThat(sql).contains("ALTER TABLE agent_runs");
        assertThat(sql).contains("ADD COLUMN model           VARCHAR(128) NULL");
        assertThat(sql).contains("fk_agent_runs_model_config");

        // 索引显式命名
        assertThat(sql).contains("idx_agent_profiles_model_config (model_config_id)");
        assertThat(sql).contains("idx_agent_runs_model_config (model_config_id)");

        // 只追加：不得出现回改历史表的 DROP / MODIFY
        assertThat(sql).doesNotContain("DROP COLUMN");
        assertThat(sql).doesNotContain("MODIFY COLUMN");
    }

    private String read(String classpathResource) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(classpathResource)) {
            assertThat(input).as("%s 应存在于 classpath", classpathResource).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
