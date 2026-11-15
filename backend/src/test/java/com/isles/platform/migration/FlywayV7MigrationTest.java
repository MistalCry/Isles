package com.isles.platform.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway V7 迁移脚本静态校验（不连接数据库）：公告、敏感词库、命中记录与索引命名。
 */
class FlywayV7MigrationTest {

    private static final List<String> V7_TABLES = List.of(
            "announcements", "sensitive_words", "sensitive_word_hits");

    @Test
    void should_contain_announcement_and_wordfilter_tables() throws IOException {
        String sql = read("db/migration/V7__announcements_and_wordfilter.sql");

        for (String table : V7_TABLES) {
            assertThat(sql).contains("CREATE TABLE " + table + " (");
        }
        assertThat(sql).contains("ENGINE = InnoDB").contains("utf8mb4_0900_ai_ci");
        assertThat(sql).contains("CONSTRAINT fk_").contains("UNIQUE KEY uk_").contains("KEY idx_");
        assertThat(sql).contains("DATETIME(3)");

        // 公告生效窗口与词条唯一
        assertThat(sql).contains("idx_announcements_status_window (status, start_at, end_at)");
        assertThat(sql).contains("uk_sensitive_words_word (word)");
        assertThat(sql).contains("idx_sensitive_word_hits_target (scene, target_id)");
    }

    private String read(String classpathResource) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(classpathResource)) {
            assertThat(input).as("%s 应存在于 classpath", classpathResource).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
