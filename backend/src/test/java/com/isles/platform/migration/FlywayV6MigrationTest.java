package com.isles.platform.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway V6 迁移脚本静态校验（不连接数据库）：确认平台后台表、索引命名与 reports 兼容改造齐备。
 */
class FlywayV6MigrationTest {

    private static final List<String> V6_TABLES = List.of(
            "platform_settings",
            "platform_setting_histories",
            "platform_audit_logs",
            "platform_admin_grants",
            "user_penalties");

    @Test
    void should_contain_all_platform_tables_and_report_adjustment() throws IOException {
        String sql = read("db/migration/V6__platform_admin_console.sql");

        for (String table : V6_TABLES) {
            assertThat(sql).contains("CREATE TABLE " + table + " (");
        }
        assertThat(sql).contains("ENGINE = InnoDB").contains("utf8mb4_0900_ai_ci");
        assertThat(sql).contains("CONSTRAINT fk_").contains("UNIQUE KEY uk_").contains("KEY idx_");
        assertThat(sql).contains("DATETIME(3)");

        // 配置表以 key 为主键；授权表一个用户一条；处罚表按用户+状态查询
        assertThat(sql).contains("PRIMARY KEY (setting_key)");
        assertThat(sql).contains("uk_platform_admin_grants_user (user_id)");
        assertThat(sql).contains("idx_user_penalties_user_status (user_id, status, end_at)");
        assertThat(sql).contains("idx_platform_audit_logs_target (target_type, target_id)");

        // reports 支持平台级举报并统一状态枚举
        assertThat(sql).contains("ALTER TABLE reports");
        assertThat(sql).contains("MODIFY COLUMN channel_id  CHAR(26)    NULL");
        assertThat(sql).contains("PENDING/ACCEPTED/REJECTED");
    }

    private String read(String classpathResource) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(classpathResource)) {
            assertThat(input).as("%s 应存在于 classpath", classpathResource).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
