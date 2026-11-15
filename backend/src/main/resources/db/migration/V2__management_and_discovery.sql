-- =============================================================================
-- 群岛（Isles）V2：管理与权限 / 内容治理 / 审计 / 统计 / 发现与推荐
-- 依据：docs/03 §2/§4/§8、docs/06 §4（后续阶段预留表）、docs/10 WP1/WP3
-- 规则：只追加、不回改；本文件不改 users 表（平台管理员字段由 V3 负责）。
-- =============================================================================

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- 1. channels：频道设置 + 全员禁言
-- -----------------------------------------------------------------------------
ALTER TABLE channels
    ADD COLUMN mute_all_until DATETIME(3) NULL COMMENT '全员禁言到期时间，NULL 表示未开启',
    ADD COLUMN settings JSON NULL COMMENT '频道扩展设置（预留：默认板块等）';

-- -----------------------------------------------------------------------------
-- 2. boards：板块软删除（板块管理需保留历史帖子引用）
-- -----------------------------------------------------------------------------
ALTER TABLE boards
    ADD COLUMN deleted_at DATETIME(3) NULL COMMENT '软删除，查询默认过滤',
    ADD KEY idx_boards_channel_deleted (channel_id, deleted_at);

-- -----------------------------------------------------------------------------
-- 3. join_requests：审批备注
-- -----------------------------------------------------------------------------
ALTER TABLE join_requests
    ADD COLUMN review_note VARCHAR(200) NULL COMMENT '审批备注（如拒绝原因）';

-- -----------------------------------------------------------------------------
-- 4. notifications：分类 / 跳转 payload / 已读时间
-- -----------------------------------------------------------------------------
ALTER TABLE notifications
    ADD COLUMN category VARCHAR(16) NOT NULL DEFAULT 'INTERACTION' COMMENT 'INTERACTION/SYSTEM',
    ADD COLUMN payload JSON NULL COMMENT '跳转与展示附加数据（postId/commentId 等冗余便于前端）',
    ADD COLUMN read_at DATETIME(3) NULL COMMENT '已读时间',
    ADD KEY idx_notifications_user_category_created (user_id, category, created_at);

-- -----------------------------------------------------------------------------
-- 5. channel_audit_logs：频道管理日志（明细操作，与统计聚合分离）
-- -----------------------------------------------------------------------------
CREATE TABLE channel_audit_logs (
    id          CHAR(26)    NOT NULL COMMENT 'ULID',
    channel_id  CHAR(26)    NOT NULL,
    actor_id    CHAR(26)    NOT NULL COMMENT '操作者',
    action      VARCHAR(64) NOT NULL COMMENT '动作，如 channel.settings / post.pin / member.mute',
    target_type VARCHAR(32) NULL COMMENT 'POST/COMMENT/MEMBER/ROLE/BOARD/INVITE/JOIN_REQUEST/REPORT/CHANNEL',
    target_id   CHAR(26)    NULL,
    detail      JSON        NULL COMMENT '操作明细（变更前后、原因等）',
    ip          VARCHAR(45) NULL,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_channel_audit_logs_channel_created (channel_id, created_at),
    KEY idx_channel_audit_logs_actor_created (actor_id, created_at),
    KEY idx_channel_audit_logs_action (action),
    CONSTRAINT fk_channel_audit_logs_channel FOREIGN KEY (channel_id) REFERENCES channels (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_channel_audit_logs_actor FOREIGN KEY (actor_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '频道管理日志';

-- -----------------------------------------------------------------------------
-- 6. reports：内容举报与处置
-- -----------------------------------------------------------------------------
CREATE TABLE reports (
    id            CHAR(26)     NOT NULL COMMENT 'ULID',
    reporter_id   CHAR(26)     NOT NULL,
    channel_id    CHAR(26)     NOT NULL,
    target_type   VARCHAR(16)  NOT NULL COMMENT 'POST/COMMENT',
    target_id     CHAR(26)     NOT NULL,
    reason        VARCHAR(32)  NOT NULL COMMENT 'SPAM/ABUSE/PORN/ILLEGAL/OTHER',
    description   VARCHAR(500) NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/ACCEPTED/REJECTED',
    handler_id    CHAR(26)     NULL,
    handle_action VARCHAR(32)  NULL COMMENT 'NONE/DELETE_CONTENT/MUTE_AUTHOR',
    handle_note   VARCHAR(500) NULL,
    handled_at    DATETIME(3)  NULL,
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_reports_status_created (status, created_at),
    KEY idx_reports_channel_status (channel_id, status),
    KEY idx_reports_target (target_type, target_id),
    KEY idx_reports_reporter (reporter_id),
    CONSTRAINT fk_reports_reporter FOREIGN KEY (reporter_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_reports_channel FOREIGN KEY (channel_id) REFERENCES channels (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_reports_handler FOREIGN KEY (handler_id) REFERENCES users (id)
        ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '内容举报';

-- -----------------------------------------------------------------------------
-- 7. daily_channel_stats：数据中心日聚合（按频道 + 自然日，可重跑）
-- -----------------------------------------------------------------------------
CREATE TABLE daily_channel_stats (
    id               CHAR(26)      NOT NULL COMMENT 'ULID',
    channel_id       CHAR(26)      NOT NULL,
    stat_date        DATE          NOT NULL COMMENT '统计自然日（Asia/Shanghai）',
    member_count     BIGINT        NOT NULL DEFAULT 0 COMMENT '当日结束成员数',
    new_members      BIGINT        NOT NULL DEFAULT 0,
    lost_members     BIGINT        NOT NULL DEFAULT 0,
    visit_uv         BIGINT        NOT NULL DEFAULT 0 COMMENT '访问人数（去重）',
    post_views       BIGINT        NOT NULL DEFAULT 0 COMMENT '浏览量（PV）',
    active_users     BIGINT        NOT NULL DEFAULT 0 COMMENT '活跃用户（发帖/评论/点赞/浏览/分享去重）',
    active_rate      DECIMAL(6, 4) NOT NULL DEFAULT 0 COMMENT '活跃率 = 活跃用户 / 成员数',
    posts            BIGINT        NOT NULL DEFAULT 0,
    comments         BIGINT        NOT NULL DEFAULT 0,
    likes            BIGINT        NOT NULL DEFAULT 0,
    shares           BIGINT        NOT NULL DEFAULT 0,
    interactions     BIGINT        NOT NULL DEFAULT 0 COMMENT '点赞 + 评论 + 分享',
    interaction_rate DECIMAL(6, 4) NOT NULL DEFAULT 0 COMMENT '互动率 = 互动 / 浏览量',
    created_at       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_daily_channel_stats_channel_date (channel_id, stat_date),
    KEY idx_daily_channel_stats_date (stat_date),
    CONSTRAINT fk_daily_channel_stats_channel FOREIGN KEY (channel_id) REFERENCES channels (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '频道日聚合统计';

-- -----------------------------------------------------------------------------
-- 8. channel_view_logs：频道访问日志（UV/PV 聚合数据源，替代 Redis HLL 的单机方案）
-- -----------------------------------------------------------------------------
CREATE TABLE channel_view_logs (
    id         CHAR(26)    NOT NULL,
    channel_id CHAR(26)    NOT NULL,
    user_id    CHAR(26)    NOT NULL COMMENT '仅记录登录用户；游客浏览不参与 UV/PV 统计',
    post_id    CHAR(26)    NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_channel_view_logs_channel_created (channel_id, created_at),
    KEY idx_channel_view_logs_channel_user (channel_id, user_id),
    CONSTRAINT fk_channel_view_logs_channel FOREIGN KEY (channel_id) REFERENCES channels (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_channel_view_logs_user FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_channel_view_logs_post FOREIGN KEY (post_id) REFERENCES posts (id)
        ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '频道访问日志';

-- -----------------------------------------------------------------------------
-- 9. channel_recommendations：频道推荐结果（规则打分生成，按用户存储）
-- -----------------------------------------------------------------------------
CREATE TABLE channel_recommendations (
    id           CHAR(26)       NOT NULL,
    user_id      CHAR(26)       NOT NULL,
    channel_id   CHAR(26)       NOT NULL,
    score        DECIMAL(12, 4) NOT NULL DEFAULT 0,
    reason       VARCHAR(200)   NULL COMMENT '推荐理由',
    generated_at DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_channel_recommendations_user_channel (user_id, channel_id),
    KEY idx_channel_recommendations_user_score (user_id, score),
    CONSTRAINT fk_channel_recommendations_user FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_channel_recommendations_channel FOREIGN KEY (channel_id) REFERENCES channels (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '频道推荐';

-- -----------------------------------------------------------------------------
-- 10. hot_keywords：搜索热词聚合（search_logs 按日聚合，绝对值覆盖写入可重跑）
-- -----------------------------------------------------------------------------
CREATE TABLE hot_keywords (
    id               CHAR(26)     NOT NULL,
    keyword          VARCHAR(100) NOT NULL,
    search_count     BIGINT       NOT NULL DEFAULT 0 COMMENT '统计窗口内搜索次数',
    last_searched_at DATETIME(3)  NULL,
    created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_hot_keywords_keyword (keyword),
    KEY idx_hot_keywords_count (search_count)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '搜索热词聚合';

-- -----------------------------------------------------------------------------
-- 11. 存量系统身份组权限点归一化（与代码 SystemRoles 对齐；可重复执行）
--     频道主：全部权限；超级管理员：除解散外全部；子板块管理员：内容管理子集；普通成员：发帖/评论
-- -----------------------------------------------------------------------------
UPDATE roles
SET permissions = JSON_ARRAY(
        'post.create', 'comment.create', 'post.pin', 'post.essence', 'post.delete', 'comment.delete',
        'channel.settings', 'channel.member.invite', 'channel.member.remove', 'channel.member.mute',
        'channel.mute_all', 'channel.role.manage', 'channel.invite.create', 'channel.invite.approve',
        'channel.visibility', 'channel.board.manage', 'channel.content.manage', 'channel.log.view',
        'channel.data.view', 'channel.ai.manage', 'channel.dissolve'),
    updated_at = CURRENT_TIMESTAMP(3)
WHERE is_system = 1 AND name = '频道主';

UPDATE roles
SET permissions = JSON_ARRAY(
        'post.create', 'comment.create', 'post.pin', 'post.essence', 'post.delete', 'comment.delete',
        'channel.settings', 'channel.member.invite', 'channel.member.remove', 'channel.member.mute',
        'channel.mute_all', 'channel.role.manage', 'channel.invite.create', 'channel.invite.approve',
        'channel.visibility', 'channel.board.manage', 'channel.content.manage', 'channel.log.view',
        'channel.data.view', 'channel.ai.manage'),
    updated_at = CURRENT_TIMESTAMP(3)
WHERE is_system = 1 AND name = '超级管理员';

UPDATE roles
SET permissions = JSON_ARRAY(
        'post.create', 'comment.create', 'post.pin', 'post.essence', 'post.delete', 'comment.delete',
        'channel.content.manage'),
    updated_at = CURRENT_TIMESTAMP(3)
WHERE is_system = 1 AND name = '子板块管理员';

UPDATE roles
SET permissions = JSON_ARRAY('post.create', 'comment.create'),
    updated_at = CURRENT_TIMESTAMP(3)
WHERE is_system = 1 AND name = '普通成员';
