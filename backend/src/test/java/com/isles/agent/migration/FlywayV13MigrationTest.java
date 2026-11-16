package com.isles.agent.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway V12 / V13 迁移脚本静态校验（不连库）：
 * 频道解散级联、代理三张表与 AI 标记列必须与 docs/06 §14/§15 完全一致。
 */
class FlywayV13MigrationTest {

    @Test
    void should_add_agent_tables_and_flags() throws IOException {
        String sql = read("db/migration/V13__ai_agents.sql");

        assertThat(sql).contains("ALTER TABLE users");
        assertThat(sql).contains("ADD COLUMN is_agent TINYINT(1) NOT NULL DEFAULT 0");
        assertThat(sql).contains("ADD COLUMN is_ai_generated TINYINT(1) NOT NULL DEFAULT 0");

        assertThat(sql).contains("CREATE TABLE agent_profiles (");
        assertThat(sql).contains("CREATE TABLE agent_memories (");
        assertThat(sql).contains("CREATE TABLE agent_runs (");

        // 字段名照 docs/12 §3，不得自创
        assertThat(sql).contains("display_name      VARCHAR(32)");
        assertThat(sql).contains("posts_per_day     INT");
        assertThat(sql).contains("comments_per_day  INT");
        assertThat(sql).contains("tools_enabled     JSON");
        assertThat(sql).contains("last_run_at       DATETIME(3)");
        assertThat(sql).contains("fingerprint CHAR(32)");
        assertThat(sql).contains("tokens_in      INT");
        assertThat(sql).contains("tokens_out     INT");

        // 一个人设对应一个账号；删除账号级联清理人设/记忆/日志
        assertThat(sql).contains("UNIQUE KEY uk_agent_profiles_user (user_id)");
        assertThat(sql).contains("CONSTRAINT fk_agent_memories_agent FOREIGN KEY (agent_id) REFERENCES users (id)");
        assertThat(sql).contains("ON DELETE CASCADE");
    }

    @Test
    void should_cascade_channel_scoped_tables_on_dissolve() throws IOException {
        String sql = read("db/migration/V12__channel_dissolve_cascade.sql");

        // 先清理历史孤儿，再把外键改成 CASCADE（软删除靠服务层，CASCADE 只为物理删除兜底）
        assertThat(sql).contains("DELETE s FROM activity_signups s");
        assertThat(sql).contains("DELETE a FROM channel_activities a");
        assertThat(sql).contains("DELETE r FROM voice_rooms r");
        assertThat(sql).contains("DELETE a FROM channel_apps a");
        assertThat(sql).contains("ALTER TABLE channel_apps DROP FOREIGN KEY fk_channel_apps_channel");
        assertThat(sql).contains("ALTER TABLE voice_rooms DROP FOREIGN KEY fk_voice_rooms_channel");
        assertThat(sql).contains("ALTER TABLE channel_activities DROP FOREIGN KEY fk_channel_activities_channel");
        assertThat(sql).contains("ON DELETE CASCADE ON UPDATE RESTRICT");
    }

    private String read(String classpathResource) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(classpathResource)) {
            assertThat(input).as("%s 应存在于 classpath", classpathResource).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
