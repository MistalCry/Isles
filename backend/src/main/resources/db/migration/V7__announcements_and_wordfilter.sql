-- =============================================================================
-- V7__announcements_and_wordfilter.sql
-- 平台运营配置：公告、敏感词库、敏感词命中记录
-- 依据：docs/11-平台管理后台设计.md §4.1（P3 部分）、§5.5、docs/07 §9.4
-- 约定：ULID CHAR(26) 主键、utf8mb4 / utf8mb4_0900_ai_ci、DATETIME(3) 存 UTC、
--       外键/唯一约束/索引显式命名（fk_/uk_/idx_）。
-- 说明：迁移只追加、不回改；本文件只新增表，不修改既有结构。
-- =============================================================================

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- 1. announcements：平台公告
--    生效判定：status='PUBLISHED' 且 (start_at 为空或已到) 且 (end_at 为空或未到)。
--    索引：idx_..._status_window 服务「取生效中的公告」；idx_..._created 服务后台倒序列表。
-- -----------------------------------------------------------------------------
CREATE TABLE announcements (
    id         CHAR(26)      NOT NULL COMMENT 'ULID',
    title      VARCHAR(120)  NOT NULL,
    content    VARCHAR(1000) NOT NULL COMMENT '纯文本；前台按文本渲染',
    level      VARCHAR(16)   NOT NULL DEFAULT 'INFO' COMMENT 'INFO/WARNING/CRITICAL',
    placement  VARCHAR(16)   NOT NULL DEFAULT 'BANNER' COMMENT 'BANNER/MODAL/INBOX',
    status     VARCHAR(16)   NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PUBLISHED/ARCHIVED',
    start_at   DATETIME(3)   NULL COMMENT '生效开始；空=立即',
    end_at     DATETIME(3)   NULL COMMENT '生效结束；空=长期',
    created_by CHAR(26)      NULL,
    created_at DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_announcements_status_window (status, start_at, end_at),
    KEY idx_announcements_created (created_at),
    CONSTRAINT fk_announcements_created_by FOREIGN KEY (created_by) REFERENCES users (id)
        ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '平台公告';

-- -----------------------------------------------------------------------------
-- 2. sensitive_words：敏感词库
--    action 语义：REVIEW=记录不阻断（默认）、MASK=命中内容打码、BLOCK=拒绝发布。
--    V7 只实现 REVIEW（记录命中），MASK/BLOCK 作为配置值预留，避免误杀。
--    索引：uk_word 防重复；idx_enabled 服务「加载启用词」。
-- -----------------------------------------------------------------------------
CREATE TABLE sensitive_words (
    id         CHAR(26)     NOT NULL COMMENT 'ULID',
    word       VARCHAR(100) NOT NULL,
    category   VARCHAR(32)  NULL COMMENT 'POLITICS/PORN/AD/ABUSE/CUSTOM',
    action     VARCHAR(16)  NOT NULL DEFAULT 'REVIEW' COMMENT 'BLOCK/REVIEW/MASK',
    enabled    TINYINT(1)   NOT NULL DEFAULT 1,
    created_by CHAR(26)     NULL,
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sensitive_words_word (word),
    KEY idx_sensitive_words_enabled (enabled),
    CONSTRAINT fk_sensitive_words_created_by FOREIGN KEY (created_by) REFERENCES users (id)
        ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '敏感词库';

-- -----------------------------------------------------------------------------
-- 3. sensitive_word_hits：命中记录（只追加）
--    word 冗余快照：词条被删除后仍可追溯；target_id 为帖子/评论 ID。
--    索引：created 服务时间倒序；word 服务「某词命中趋势」；target 服务「某内容命中了什么」。
-- -----------------------------------------------------------------------------
CREATE TABLE sensitive_word_hits (
    id         CHAR(26)     NOT NULL COMMENT 'ULID',
    word_id    CHAR(26)     NULL,
    word       VARCHAR(100) NOT NULL COMMENT '命中词快照',
    scene      VARCHAR(16)  NOT NULL COMMENT 'POST/COMMENT',
    target_id  CHAR(26)     NULL,
    user_id    CHAR(26)     NULL,
    action     VARCHAR(16)  NOT NULL COMMENT '命中时的处置：BLOCK/REVIEW/MASK',
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_sensitive_word_hits_created (created_at),
    KEY idx_sensitive_word_hits_word (word_id, created_at),
    KEY idx_sensitive_word_hits_target (scene, target_id),
    CONSTRAINT fk_sensitive_word_hits_word FOREIGN KEY (word_id) REFERENCES sensitive_words (id)
        ON DELETE SET NULL ON UPDATE RESTRICT,
    CONSTRAINT fk_sensitive_word_hits_user FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '敏感词命中记录';
