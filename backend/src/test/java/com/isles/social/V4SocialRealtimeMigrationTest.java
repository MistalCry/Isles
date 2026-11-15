package com.isles.social;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V4 社交/实时迁移脚本静态校验（不连接数据库）：
 * 确认三张表、显式命名约束、索引、DATETIME(3) 与"不改既有表"的约束均满足，避免交付漏表。
 */
class V4SocialRealtimeMigrationTest {

    private static final String MIGRATION = "db/migration/V4__social_realtime.sql";

    @Test
    void should_contain_friend_and_dm_tables_with_required_conventions() throws IOException {
        String sql;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(input).as("V4__social_realtime.sql 应存在于 classpath:db/migration").isNotNull();
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql).contains("CREATE TABLE friend_relations (");
        assertThat(sql).contains("CREATE TABLE dm_conversations (");
        assertThat(sql).contains("CREATE TABLE dm_messages (");

        // 显式命名约束（docs/00 §8.1）
        assertThat(sql).contains("UNIQUE KEY uk_friend_relations_user_target");
        assertThat(sql).contains("UNIQUE KEY uk_dm_conversations_pair");
        assertThat(sql).contains("UNIQUE KEY uk_dm_messages_client");
        assertThat(sql).contains("CONSTRAINT fk_friend_relations_user");
        assertThat(sql).contains("CONSTRAINT fk_dm_messages_conversation");
        assertThat(sql).contains("KEY idx_dm_messages_conversation_id");
        assertThat(sql).contains("KEY idx_friend_relations_target_status");

        // 时间统一 DATETIME(3)、utf8mb4、InnoDB
        assertThat(sql).contains("DATETIME(3)").contains("utf8mb4_0900_ai_ci").contains("ENGINE = InnoDB");

        // 私信状态与分享字段
        assertThat(sql).contains("PENDING/ACCEPTED/REJECTED/BLOCKED");
        assertThat(sql).contains("TEXT/IMAGE/POST");
        assertThat(sql).contains("postId");

        // 只追加：不允许修改既有业务表
        assertThat(sql).doesNotContain("ALTER TABLE users");
        assertThat(sql).doesNotContain("ALTER TABLE channels");
        assertThat(sql).doesNotContain("ALTER TABLE posts");
    }

    @Test
    void should_use_expected_collation_and_comments() throws IOException {
        String sql;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(input).isNotNull();
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(sql).contains("COMMENT = '好友关系");
        assertThat(sql).contains("COMMENT = '私信会话'");
        assertThat(sql).contains("COMMENT = '私信消息'");
    }
}
