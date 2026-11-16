-- =============================================================================
-- V11__ai_conversation_scope_length.sql
-- 频道助手多会话：ai_conversations.scope 需要容纳 CHANNEL_ASSISTANT
--
-- 背景：V3 建表时 scope 为 VARCHAR(16)，只预留 GLOBAL / CHANNEL；
--       V10 引入 CHANNEL_ASSISTANT（17 个字符）后插入会报
--       "Data too long for column 'scope'"（500）。这里放宽到 VARCHAR(32) 并补注释。
-- 约定：迁移只追加不回改；本文件只改列定义，不动数据。
-- =============================================================================

SET NAMES utf8mb4;

ALTER TABLE ai_conversations
    MODIFY COLUMN scope VARCHAR(32) NOT NULL DEFAULT 'GLOBAL'
        COMMENT 'GLOBAL / CHANNEL / CHANNEL_ASSISTANT';
