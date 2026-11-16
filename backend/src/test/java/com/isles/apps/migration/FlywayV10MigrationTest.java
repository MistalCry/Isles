package com.isles.apps.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway V10 迁移脚本静态校验（不连接数据库）：
 * 频道应用、语音房与麦位、频道活动与报名的表结构、唯一键与索引命名。
 */
class FlywayV10MigrationTest {

    private static final List<String> V10_TABLES = List.of(
            "channel_apps", "voice_rooms", "voice_room_seats", "channel_activities", "activity_signups");

    @Test
    void should_create_apps_voice_and_activity_tables() throws IOException {
        String sql = read("db/migration/V10__channel_apps_voice_and_activities.sql");

        for (String table : V10_TABLES) {
            assertThat(sql).contains("CREATE TABLE " + table + " (");
        }
        assertThat(sql).contains("ENGINE = InnoDB").contains("utf8mb4_0900_ai_ci");
        assertThat(sql).contains("CONSTRAINT fk_").contains("UNIQUE KEY uk_").contains("KEY idx_");

        // 字段名必须与 docs/06 §13 一致（前台/后台都按这些名字对接）
        assertThat(sql).contains("app_type    VARCHAR(16)").contains("sort_order  INT");
        assertThat(sql).contains("max_seats  INT").contains("seat_index INT").contains("muted      TINYINT(1)");
        assertThat(sql).contains("reminder_sent_at  DATETIME(3)");

        // 抢座/报名幂等依赖唯一键
        assertThat(sql).contains("uk_voice_room_seats_room_user (room_id, user_id)");
        assertThat(sql).contains("uk_voice_room_seats_room_seat (room_id, seat_index)");
        assertThat(sql).contains("uk_activity_signups_activity_user (activity_id, user_id)");
    }

    private String read(String classpathResource) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(classpathResource)) {
            assertThat(input).as("%s 应存在于 classpath", classpathResource).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
