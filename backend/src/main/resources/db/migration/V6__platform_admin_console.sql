-- =============================================================================
-- V6__platform_admin_console.sql
-- 平台管理后台：运行时配置、配置历史、平台审计、管理员授权、用户处罚
-- 依据：docs/11-平台管理后台设计.md（§4 数据模型）、docs/03 §4.16、docs/07 §9
-- 约定：ULID CHAR(26) 主键、utf8mb4 / utf8mb4_0900_ai_ci、DATETIME(3) 存 UTC、
--       外键/唯一约束/索引显式命名（fk_/uk_/idx_）。
-- 说明：迁移只追加、不回改；本文件只新增表 + 放宽 reports 的平台级举报约束。
-- =============================================================================

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- 1. platform_settings：运行时配置覆盖值
--    默认值由代码 com.isles.platform.setting.SettingDefinitions 定义（唯一事实来源），
--    本表只存被后台覆盖过的键；恢复默认 = 删除对应行。
--    索引：主键 setting_key 覆盖「按键读取」，表规模仅几十行，不额外建索引。
-- -----------------------------------------------------------------------------
CREATE TABLE platform_settings (
    setting_key   VARCHAR(100) NOT NULL COMMENT '配置键，如 site.name',
    setting_value TEXT         NULL COMMENT '覆盖值（字符串形式，按 value_type 解析）',
    updated_by    CHAR(26)     NULL COMMENT '最后修改人',
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (setting_key),
    CONSTRAINT fk_platform_settings_updated_by FOREIGN KEY (updated_by) REFERENCES users (id)
        ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '平台运行时配置';

-- -----------------------------------------------------------------------------
-- 2. platform_setting_histories：配置变更历史（支持回滚与追责）
--    索引：idx_..._key_created 服务「按配置键看历史」；idx_..._actor 服务「按操作人查」。
-- -----------------------------------------------------------------------------
CREATE TABLE platform_setting_histories (
    id          CHAR(26)     NOT NULL COMMENT 'ULID',
    setting_key VARCHAR(100) NOT NULL,
    old_value   TEXT         NULL COMMENT '变更前（null=此前未覆盖）',
    new_value   TEXT         NULL COMMENT '变更后（null=恢复默认）',
    actor_id    CHAR(26)     NULL,
    ip          VARCHAR(45)  NULL,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_platform_setting_histories_key_created (setting_key, created_at),
    KEY idx_platform_setting_histories_actor (actor_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '平台配置变更历史';

-- -----------------------------------------------------------------------------
-- 3. platform_audit_logs：平台操作审计（只追加，不提供更新/删除接口）
--    actor_name 冗余快照：用户改名或注销后仍可追溯。
--    索引：created 服务时间倒序列表；actor/action 服务筛选；target 服务「查某个对象被谁动过」。
-- -----------------------------------------------------------------------------
CREATE TABLE platform_audit_logs (
    id          CHAR(26)     NOT NULL COMMENT 'ULID',
    actor_id    CHAR(26)     NULL,
    actor_name  VARCHAR(32)  NULL COMMENT '操作人快照',
    action      VARCHAR(64)  NOT NULL COMMENT 'platform.config.update / platform.user.ban',
    target_type VARCHAR(32)  NULL COMMENT 'USER/CHANNEL/POST/COMMENT/REPORT/CONFIG/ADMIN',
    target_id   CHAR(26)     NULL,
    detail      JSON         NULL COMMENT 'before/after、原因等',
    ip          VARCHAR(45)  NULL,
    user_agent  VARCHAR(255) NULL,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_platform_audit_logs_created (created_at),
    KEY idx_platform_audit_logs_actor_created (actor_id, created_at),
    KEY idx_platform_audit_logs_action_created (action, created_at),
    KEY idx_platform_audit_logs_target (target_type, target_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '平台操作审计';

-- -----------------------------------------------------------------------------
-- 4. platform_admin_grants：平台管理员授权
--    一个用户同时只保留一条授权（撤销即删除行，重新授权插新行）。
--    索引：uk_user 保证唯一；idx_role 服务「按角色列管理员」。
-- -----------------------------------------------------------------------------
CREATE TABLE platform_admin_grants (
    id         CHAR(26)    NOT NULL COMMENT 'ULID',
    user_id    CHAR(26)    NOT NULL,
    role       VARCHAR(24) NOT NULL COMMENT 'SUPER_ADMIN/OPERATOR/MODERATOR/VIEWER',
    granted_by CHAR(26)    NULL,
    granted_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at DATETIME(3) NULL COMMENT 'null=长期有效',
    PRIMARY KEY (id),
    UNIQUE KEY uk_platform_admin_grants_user (user_id),
    KEY idx_platform_admin_grants_role (role),
    CONSTRAINT fk_platform_admin_grants_user FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '平台管理员授权';

-- -----------------------------------------------------------------------------
-- 5. user_penalties：用户处罚记录（封禁/禁言/警告）
--    与 users.status 分工：users.status 表达「当前是否可登录」，本表保留期限、原因与历史。
--    索引：idx_user_status_end 服务「查生效中的处罚」；idx_created 服务后台列表倒序。
-- -----------------------------------------------------------------------------
CREATE TABLE user_penalties (
    id          CHAR(26)     NOT NULL COMMENT 'ULID',
    user_id     CHAR(26)     NOT NULL,
    type        VARCHAR(16)  NOT NULL COMMENT 'BAN/MUTE/WARN',
    scope       VARCHAR(16)  NOT NULL DEFAULT 'GLOBAL' COMMENT 'GLOBAL/CHANNEL',
    channel_id  CHAR(26)     NULL COMMENT 'scope=CHANNEL 时的频道',
    reason      VARCHAR(200) NOT NULL,
    report_id   CHAR(26)     NULL COMMENT '关联举报',
    operator_id CHAR(26)     NULL,
    start_at    DATETIME(3)  NOT NULL,
    end_at      DATETIME(3)  NULL COMMENT 'null=永久',
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/EXPIRED/REVOKED',
    revoked_by  CHAR(26)     NULL,
    revoked_at  DATETIME(3)  NULL,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_user_penalties_user_status (user_id, status, end_at),
    KEY idx_user_penalties_created (created_at),
    CONSTRAINT fk_user_penalties_user FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户处罚记录';

-- -----------------------------------------------------------------------------
-- 6. reports：支持平台级举报（举报用户/频道时无所属频道）
--    状态枚举统一为既有定义 PENDING/ACCEPTED/REJECTED（此前平台后台写入
--    DISMISSED/RESOLVED，与频道侧 Report.Status 分裂，见 docs/11 §1.3 D1）。
-- -----------------------------------------------------------------------------
ALTER TABLE reports
    MODIFY COLUMN channel_id  CHAR(26)    NULL COMMENT '所属频道；平台级举报为空',
    MODIFY COLUMN target_type VARCHAR(16) NOT NULL COMMENT 'POST/COMMENT/USER/CHANNEL',
    MODIFY COLUMN status      VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/ACCEPTED/REJECTED',
    MODIFY COLUMN handle_action VARCHAR(32) NULL COMMENT 'NONE/DELETE_CONTENT/MUTE_AUTHOR/WARN';
