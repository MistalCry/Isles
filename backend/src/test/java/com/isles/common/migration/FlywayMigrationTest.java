package com.isles.common.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway V1 迁移脚本静态校验（不连接数据库）：确认 docs/06 §3 的 17 张 M1 表、ULID 主键、
 * utf8mb4 字符集与显式命名约束均存在，避免交付时漏表。
 */
class FlywayMigrationTest {

    private static final List<String> M1_TABLES = List.of(
            "users", "refresh_tokens", "channels", "roles", "channel_members", "boards",
            "channel_invites", "join_requests", "posts", "post_media", "comments",
            "post_likes", "comment_likes", "notifications", "search_history", "search_logs", "media_files");

    @Test
    void should_contain_all_m1_tables_with_required_conventions() throws IOException {
        String sql;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("db/migration/V1__init.sql")) {
            assertThat(input).as("V1__init.sql 应存在于 classpath:db/migration").isNotNull();
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(M1_TABLES).hasSize(17);
        for (String table : M1_TABLES) {
            assertThat(sql).contains("CREATE TABLE " + table + " (");
        }
        assertThat(sql).contains("CHAR(26)").contains("utf8mb4_0900_ai_ci").contains("ENGINE = InnoDB");
        // 显式命名约束
        assertThat(sql).contains("CONSTRAINT fk_");
        assertThat(sql).contains("UNIQUE KEY uk_");
        assertThat(sql).contains("KEY idx_");
        // 时间统一 DATETIME(3)
        assertThat(sql).contains("DATETIME(3)");
    }
}
