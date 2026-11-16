-- =============================================================================
-- V13__ai_agents.sql
-- AI 用户代理 P0：账号标记、内容标记、人设、记忆、运行日志
--
-- 依据：docs/12-AI用户代理设计.md §3（字段名与 docs/12 一一对应，不自创列）
-- 约定：ULID CHAR(26) 主键、utf8mb4 / utf8mb4_0900_ai_ci、DATETIME(3) 存 UTC、
--       外键/唯一键/索引显式命名（fk_/uk_/idx_），迁移只追加不回改。
-- =============================================================================

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- 1. 账号与内容标记：AI 标识是硬性要求，不可关闭（docs/12 §6）
-- -----------------------------------------------------------------------------
ALTER TABLE users
    ADD COLUMN is_agent TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否 AI 代理账号' AFTER status,
    ADD KEY idx_users_is_agent (is_agent, status);

ALTER TABLE posts
    ADD COLUMN is_ai_generated TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否 AI 代理生成' AFTER is_essence;

ALTER TABLE comments
    ADD COLUMN is_ai_generated TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否 AI 代理生成' AFTER status;

-- -----------------------------------------------------------------------------
-- 2. agent_profiles：人设（一个账号一条）
--    active_hours / interests / channels / tools_enabled 均为 JSON：
--    active_hours 形如 [[20,24],[0,1]]，跨零点用两段表示。
-- -----------------------------------------------------------------------------
CREATE TABLE agent_profiles (
    id                CHAR(26)     NOT NULL COMMENT 'ULID',
    user_id           CHAR(26)     NOT NULL COMMENT '关联 users.id（代理账号）',
    display_name      VARCHAR(32)  NOT NULL,
    bio               VARCHAR(200) NULL,
    interests         JSON         NULL COMMENT '兴趣标签，如 ["minecraft","建筑"]',
    tone              VARCHAR(32)  NOT NULL DEFAULT 'casual' COMMENT 'casual / enthusiastic / dry',
    active_hours      JSON         NULL COMMENT '活跃时段，如 [[20,24],[0,1]]',
    posts_per_day     INT          NOT NULL DEFAULT 2,
    comments_per_day  INT          NOT NULL DEFAULT 8,
    channels          JSON         NULL COMMENT '允许活动的频道 id 列表；空=全站公开频道',
    tools_enabled     JSON         NULL COMMENT '启用工具名列表；空=默认 P0 全集',
    status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / PAUSED',
    last_run_at       DATETIME(3)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_profiles_user (user_id),
    KEY idx_agent_profiles_status (status, last_run_at),
    CONSTRAINT fk_agent_profiles_user FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AI 代理人设';

-- -----------------------------------------------------------------------------
-- 3. agent_memories：记忆（话题指纹用于 7 天内不重复同一话题）
-- -----------------------------------------------------------------------------
CREATE TABLE agent_memories (
    id          CHAR(26)     NOT NULL COMMENT 'ULID',
    agent_id    CHAR(26)     NOT NULL COMMENT '关联 users.id',
    kind        VARCHAR(16)  NOT NULL COMMENT 'TOPIC / OPINION / CATCHPHRASE / FACT',
    content     VARCHAR(500) NOT NULL,
    fingerprint CHAR(32)     NOT NULL COMMENT '归一化指纹（去标点/小写后的 MD5）',
    source_ref  VARCHAR(64)  NULL COMMENT '来源：post_id / 外部 URL',
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_agent_memories_agent_kind (agent_id, kind, created_at),
    KEY idx_agent_memories_fingerprint (agent_id, fingerprint),
    CONSTRAINT fk_agent_memories_agent FOREIGN KEY (agent_id) REFERENCES users (id)
        ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AI 代理记忆';

-- -----------------------------------------------------------------------------
-- 4. agent_runs：运行日志（每次 tick 一条，可回放决策与工具调用）
-- -----------------------------------------------------------------------------
CREATE TABLE agent_runs (
    id             CHAR(26)     NOT NULL COMMENT 'ULID',
    agent_id       CHAR(26)     NOT NULL COMMENT '关联 users.id',
    started_at     DATETIME(3)  NOT NULL,
    finished_at    DATETIME(3)  NULL,
    plan_json      JSON         NULL COMMENT '模型输出的行动计划（原文）',
    tool_calls_json JSON        NULL COMMENT '工具名、入参、结果摘要',
    output_type    VARCHAR(16)  NOT NULL DEFAULT 'NONE' COMMENT 'POST / COMMENT / LIKE / NONE',
    output_id      CHAR(26)     NULL,
    status         VARCHAR(16)  NOT NULL COMMENT 'SUCCESS / GUARDED / FAILED / SKIPPED',
    error          VARCHAR(500) NULL,
    tokens_in      INT          NOT NULL DEFAULT 0,
    tokens_out     INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_agent_runs_agent_time (agent_id, started_at),
    KEY idx_agent_runs_status_time (status, started_at),
    CONSTRAINT fk_agent_runs_agent FOREIGN KEY (agent_id) REFERENCES users (id)
        ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AI 代理运行日志';
