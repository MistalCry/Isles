-- =============================================================================
-- 群岛（Isles）V3：AI 能力（频道问问 / 频道助手）与平台治理
-- 依据：docs/03 §4.11/§4.14-4.17、docs/06 §4（M3 预留表）、docs/07 §9（AI 内容标识）
-- 约定：ULID CHAR(26) 主键、utf8mb4 / utf8mb4_0900_ai_ci、DATETIME(3) 存 UTC、
--       外键/唯一约束/索引显式命名（fk_/uk_/idx_）。
-- 说明：迁移文件只追加、不回改；本文件为 V3，只新增表与 users 的 platform_role 列。
-- =============================================================================

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- users.platform_role：平台角色（USER/ADMIN），平台管理后台鉴权依据（docs/02 §5.3）
-- 兼容配置白名单 app.platform-admin-usernames（见 AdminAuthService）
-- -----------------------------------------------------------------------------
ALTER TABLE users
    ADD COLUMN platform_role VARCHAR(16) NOT NULL DEFAULT 'USER' COMMENT '平台角色：USER/ADMIN' AFTER status,
    ADD KEY idx_users_platform_role (platform_role);

-- -----------------------------------------------------------------------------
-- ai_model_configs：模型配置（LMStudio / OpenAI 兼容，docs/03 FR-PADM-01、FR-AIQA-10）
-- api_key 使用 AES-GCM 加密存储（app.ai.api-key-secret）；未配置密钥时 api_key_encrypted=0
-- 注意：API Key 永不出现在接口响应中，只返回是否已配置
-- -----------------------------------------------------------------------------
CREATE TABLE ai_model_configs (
    id                CHAR(26)     NOT NULL,
    name              VARCHAR(64)  NOT NULL COMMENT '配置名称（展示用，唯一）',
    base_url          VARCHAR(500) NOT NULL COMMENT 'OpenAI 兼容 Base URL，如 http://localhost:1234/v1',
    api_key           VARCHAR(1000) NULL COMMENT 'API Key 密文（或未配置密钥时的明文）',
    api_key_encrypted TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '1=AES-GCM 密文',
    model             VARCHAR(128) NOT NULL COMMENT '模型 ID，如 qwen2.5-vl-7b',
    vision_enabled    TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否支持图片（多模态）',
    reasoning_enabled TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否支持深度思考/推理参数',
    is_default        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '默认模型',
    enabled           TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '启用状态',
    timeout_seconds   INT          NOT NULL DEFAULT 60 COMMENT '请求超时（秒）',
    max_tokens        INT          NOT NULL DEFAULT 2048 COMMENT '最大输出 token',
    created_by        CHAR(26)     NULL,
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_model_configs_name (name),
    KEY idx_ai_model_configs_enabled (enabled, is_default),
    CONSTRAINT fk_ai_model_configs_creator FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AI 模型配置';

-- -----------------------------------------------------------------------------
-- ai_conversations：AI 会话（GLOBAL=频道问问 / CHANNEL=频道助手，docs/03 FR-AIQA-06）
-- -----------------------------------------------------------------------------
CREATE TABLE ai_conversations (
    id              CHAR(26)    NOT NULL,
    user_id         CHAR(26)    NOT NULL,
    scope           VARCHAR(16) NOT NULL DEFAULT 'GLOBAL' COMMENT 'GLOBAL / CHANNEL',
    channel_id      CHAR(26)    NULL COMMENT 'scope=CHANNEL 时的频道',
    title           VARCHAR(120) NULL,
    model_config_id CHAR(26)    NULL COMMENT '最近使用的模型配置',
    message_count   INT         NOT NULL DEFAULT 0,
    last_message_at DATETIME(3) NULL,
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at      DATETIME(3) NULL,
    PRIMARY KEY (id),
    KEY idx_ai_conversations_user (user_id, deleted_at, updated_at),
    KEY idx_ai_conversations_channel (channel_id, scope),
    CONSTRAINT fk_ai_conversations_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_conversations_channel FOREIGN KEY (channel_id) REFERENCES channels (id) ON DELETE SET NULL ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_conversations_model FOREIGN KEY (model_config_id) REFERENCES ai_model_configs (id) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AI 会话';

-- -----------------------------------------------------------------------------
-- ai_messages：AI 消息（用户提问 / 助手回答；推理内容与来源引用 JSON 落库）
-- role: USER / ASSISTANT / SYSTEM
-- status: COMPLETED / STREAMING / ABORTED / FAILED
-- -----------------------------------------------------------------------------
CREATE TABLE ai_messages (
    id                CHAR(26)    NOT NULL,
    conversation_id   CHAR(26)    NOT NULL,
    user_id           CHAR(26)    NULL COMMENT '提问用户（助手回答也记录，便于配额统计）',
    role              VARCHAR(16) NOT NULL COMMENT 'USER/ASSISTANT/SYSTEM',
    content           MEDIUMTEXT  NOT NULL,
    reasoning_content MEDIUMTEXT  NULL COMMENT '深度思考过程（可选）',
    images            JSON        NULL COMMENT '用户上传图片 [{url}]',
    sources           JSON        NULL COMMENT '回答引用的帖子 [{id,title,channelId,channelName,excerpt,url}]',
    model             VARCHAR(128) NULL COMMENT '生成时使用的模型 ID',
    model_config_id   CHAR(26)    NULL,
    token_in          INT         NULL,
    token_out         INT         NULL,
    status            VARCHAR(16) NOT NULL DEFAULT 'COMPLETED' COMMENT 'COMPLETED/STREAMING/ABORTED/FAILED',
    created_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_ai_messages_conversation (conversation_id, created_at),
    KEY idx_ai_messages_user_created (user_id, created_at),
    CONSTRAINT fk_ai_messages_conversation FOREIGN KEY (conversation_id) REFERENCES ai_conversations (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_messages_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_messages_model FOREIGN KEY (model_config_id) REFERENCES ai_model_configs (id) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AI 消息';

-- -----------------------------------------------------------------------------
-- channel_ai_settings：频道助手设置（虚拟账号 persona + 自动任务开关/频率）
-- 一个频道最多一条；assistant_user_id 指向助手虚拟账号（users 中 username 前缀 aiassistant_）
-- 时间均按 UTC；daily_summary_hour 为 0-23
-- -----------------------------------------------------------------------------
CREATE TABLE channel_ai_settings (
    id                           CHAR(26)     NOT NULL,
    channel_id                   CHAR(26)     NOT NULL,
    assistant_user_id            CHAR(26)     NULL COMMENT '助手虚拟账号',
    nickname                     VARCHAR(32)  NOT NULL DEFAULT '频道助手',
    avatar_url                   VARCHAR(500) NULL,
    cover_url                    VARCHAR(500) NULL,
    bio                          VARCHAR(500) NULL,
    persona                      VARCHAR(2000) NULL COMMENT '助手设定/性格描述',
    enabled                      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '频道助手总开关',
    summary_daily_enabled        TINYINT(1)   NOT NULL DEFAULT 0,
    summary_weekly_enabled       TINYINT(1)   NOT NULL DEFAULT 0,
    monthly_contributors_enabled TINYINT(1)   NOT NULL DEFAULT 0,
    auto_post_enabled            TINYINT(1)   NOT NULL DEFAULT 0,
    auto_comment_enabled         TINYINT(1)   NOT NULL DEFAULT 0,
    auto_like_enabled            TINYINT(1)   NOT NULL DEFAULT 0,
    daily_summary_hour           TINYINT      NOT NULL DEFAULT 9 COMMENT 'UTC 小时（0-23）',
    auto_post_per_day            INT          NOT NULL DEFAULT 1,
    auto_comment_per_day         INT          NOT NULL DEFAULT 3,
    auto_like_per_day            INT          NOT NULL DEFAULT 10,
    created_at                   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at                   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_channel_ai_settings_channel (channel_id),
    CONSTRAINT fk_channel_ai_settings_channel FOREIGN KEY (channel_id) REFERENCES channels (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_channel_ai_settings_assistant FOREIGN KEY (assistant_user_id) REFERENCES users (id) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '频道助手设置';

-- -----------------------------------------------------------------------------
-- ai_task_logs：频道助手自动任务执行日志（幂等键 = channel + task_type + frequency_key）
-- task_type: DAILY_SUMMARY / WEEKLY_SUMMARY / MONTHLY_CONTRIBUTORS / AUTO_POST / AUTO_COMMENT / AUTO_LIKE
-- status: RUNNING / SUCCESS / SKIPPED / FAILED
-- frequency_key: 日报 2026-11-14；周报 2026-W46；月榜 2026-11；自动发帖 2026-11-14#1
-- -----------------------------------------------------------------------------
CREATE TABLE ai_task_logs (
    id            CHAR(26)     NOT NULL,
    channel_id    CHAR(26)     NOT NULL,
    setting_id    CHAR(26)     NULL,
    task_type     VARCHAR(32)  NOT NULL,
    frequency_key VARCHAR(40)  NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'RUNNING',
    result_text   TEXT         NULL COMMENT '生成内容摘要',
    result        JSON         NULL COMMENT '结构化结果（如贡献榜条目）',
    post_id       CHAR(26)     NULL,
    comment_id    CHAR(26)     NULL,
    error_message VARCHAR(1000) NULL,
    started_at    DATETIME(3)  NULL,
    finished_at   DATETIME(3)  NULL,
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_task_logs_channel_type_key (channel_id, task_type, frequency_key),
    KEY idx_ai_task_logs_channel_created (channel_id, created_at),
    KEY idx_ai_task_logs_status (status, created_at),
    CONSTRAINT fk_ai_task_logs_channel FOREIGN KEY (channel_id) REFERENCES channels (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_task_logs_setting FOREIGN KEY (setting_id) REFERENCES channel_ai_settings (id) ON DELETE SET NULL ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_task_logs_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE SET NULL ON UPDATE RESTRICT,
    CONSTRAINT fk_ai_task_logs_comment FOREIGN KEY (comment_id) REFERENCES comments (id) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '频道助手任务执行日志';
