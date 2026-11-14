-- =============================================================================
-- 群岛（Isles）M1 数据库初始化
-- 依据：docs/06-数据库设计.md §3（M1 表结构）
-- 约定：ULID CHAR(26) 主键、utf8mb4 / utf8mb4_0900_ai_ci、DATETIME(3) 存 UTC、
--       外键/唯一约束/索引显式命名（fk_/uk_/idx_）。
-- 说明：迁移文件只追加、不回改；本文件为 V1，后续变更使用 V2+。
-- =============================================================================

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- 3.1 users：用户账号
-- -----------------------------------------------------------------------------
CREATE TABLE users (
    id            CHAR(26)     NOT NULL COMMENT 'ULID',
    username      VARCHAR(32)  NOT NULL COMMENT '登录名',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希',
    nickname      VARCHAR(32)  NOT NULL COMMENT '展示名',
    avatar_url    VARCHAR(500) NULL COMMENT '头像',
    signature     VARCHAR(200) NULL COMMENT '签名',
    gender        VARCHAR(16)  NULL COMMENT 'MALE/FEMALE/UNKNOWN',
    region        VARCHAR(64)  NULL COMMENT '地区',
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/BANNED/DISABLED',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at    DATETIME(3)  NULL COMMENT '软删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username),
    KEY idx_users_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户账号';

-- -----------------------------------------------------------------------------
-- 3.2 refresh_tokens：刷新令牌（只存 SHA-256 哈希，可撤销）
-- -----------------------------------------------------------------------------
CREATE TABLE refresh_tokens (
    id         CHAR(26)    NOT NULL,
    user_id    CHAR(26)    NOT NULL,
    token_hash CHAR(64)    NOT NULL COMMENT 'SHA-256 十六进制',
    expires_at DATETIME(3) NOT NULL,
    revoked_at DATETIME(3) NULL,
    user_agent VARCHAR(255) NULL,
    ip         VARCHAR(45) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_refresh_tokens_hash (token_hash),
    KEY idx_refresh_tokens_user (user_id),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '刷新令牌';

-- -----------------------------------------------------------------------------
-- 3.3 channels：频道
-- -----------------------------------------------------------------------------
CREATE TABLE channels (
    id            CHAR(26)     NOT NULL,
    channel_no    BIGINT       NOT NULL COMMENT '对外频道号',
    name          VARCHAR(64)  NOT NULL,
    avatar_url    VARCHAR(500) NOT NULL,
    cover_url     VARCHAR(500) NULL,
    description   VARCHAR(1000) NULL,
    type          VARCHAR(16)  NOT NULL COMMENT 'PUBLIC/PRIVATE',
    visibility    VARCHAR(16)  NOT NULL COMMENT 'PUBLIC_VISIBLE/MEMBERS_ONLY',
    owner_id      CHAR(26)     NOT NULL COMMENT '频道主',
    member_count  BIGINT       NOT NULL DEFAULT 0,
    post_count    BIGINT       NOT NULL DEFAULT 0,
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISSOLVED/BANNED',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at    DATETIME(3)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_channels_no (channel_no),
    KEY idx_channels_owner (owner_id),
    KEY idx_channels_type_status (type, status),
    CONSTRAINT fk_channels_owner FOREIGN KEY (owner_id) REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '频道';

-- -----------------------------------------------------------------------------
-- 3.6 boards：板块（必须先于 roles 创建，roles.scope_board_id 引用它）
-- -----------------------------------------------------------------------------
CREATE TABLE boards (
    id          CHAR(26)     NOT NULL,
    channel_id  CHAR(26)     NOT NULL,
    name        VARCHAR(32)  NOT NULL,
    description VARCHAR(200) NULL,
    sort        INT          NOT NULL DEFAULT 0,
    post_count  BIGINT       NOT NULL DEFAULT 0,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_boards_channel_name (channel_id, name),
    KEY idx_boards_channel_sort (channel_id, sort),
    CONSTRAINT fk_boards_channel FOREIGN KEY (channel_id) REFERENCES channels (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '频道板块';

-- -----------------------------------------------------------------------------
-- 3.4 roles：身份组
-- -----------------------------------------------------------------------------
CREATE TABLE roles (
    id             CHAR(26)     NOT NULL,
    channel_id     CHAR(26)     NOT NULL,
    name           VARCHAR(32)  NOT NULL,
    description    VARCHAR(200) NULL,
    permissions    JSON         NOT NULL COMMENT '权限点数组，如 ["post.create"]',
    scope_board_id CHAR(26)     NULL COMMENT '子板块管理员范围',
    is_system      TINYINT(1)   NOT NULL DEFAULT 0,
    sort           INT          NOT NULL DEFAULT 0,
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_roles_channel_name (channel_id, name),
    KEY idx_roles_channel (channel_id),
    CONSTRAINT fk_roles_channel FOREIGN KEY (channel_id) REFERENCES channels (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_roles_board FOREIGN KEY (scope_board_id) REFERENCES boards (id) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '频道身份组';

-- -----------------------------------------------------------------------------
-- 3.5 channel_members：频道成员
-- -----------------------------------------------------------------------------
CREATE TABLE channel_members (
    id          CHAR(26)    NOT NULL,
    channel_id  CHAR(26)    NOT NULL,
    user_id     CHAR(26)    NOT NULL,
    role_id     CHAR(26)    NOT NULL,
    status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/MUTED/LEFT/KICKED',
    muted_until DATETIME(3) NULL COMMENT '个人禁言到期时间',
    joined_at   DATETIME(3) NOT NULL,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_channel_members_channel_user (channel_id, user_id),
    KEY idx_channel_members_user (user_id),
    KEY idx_channel_members_role (role_id),
    CONSTRAINT fk_channel_members_channel FOREIGN KEY (channel_id) REFERENCES channels (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_channel_members_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_channel_members_role FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '频道成员';

-- -----------------------------------------------------------------------------
-- 3.7 channel_invites：邀请链接
-- -----------------------------------------------------------------------------
CREATE TABLE channel_invites (
    id                CHAR(26)  NOT NULL,
    channel_id        CHAR(26)  NOT NULL,
    token_hash        CHAR(64)  NOT NULL COMMENT 'SHA-256 十六进制',
    requires_approval TINYINT(1) NOT NULL DEFAULT 0,
    expires_at        DATETIME(3) NULL,
    max_uses          INT       NULL,
    used_count        INT       NOT NULL DEFAULT 0,
    created_by        CHAR(26)  NOT NULL,
    revoked_at        DATETIME(3) NULL,
    created_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_channel_invites_token (token_hash),
    KEY idx_channel_invites_channel (channel_id),
    CONSTRAINT fk_channel_invites_channel FOREIGN KEY (channel_id) REFERENCES channels (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_channel_invites_creator FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '频道邀请链接';

-- -----------------------------------------------------------------------------
-- 3.7 join_requests：加入申请
-- -----------------------------------------------------------------------------
CREATE TABLE join_requests (
    id          CHAR(26)    NOT NULL,
    channel_id  CHAR(26)    NOT NULL,
    user_id     CHAR(26)    NOT NULL,
    invite_id   CHAR(26)    NULL,
    status      VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED',
    reviewed_by CHAR(26)    NULL,
    reviewed_at DATETIME(3) NULL,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_join_requests_channel_status (channel_id, status),
    KEY idx_join_requests_user (user_id),
    CONSTRAINT fk_join_requests_channel FOREIGN KEY (channel_id) REFERENCES channels (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_join_requests_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_join_requests_invite FOREIGN KEY (invite_id) REFERENCES channel_invites (id) ON DELETE SET NULL ON UPDATE RESTRICT,
    CONSTRAINT fk_join_requests_reviewer FOREIGN KEY (reviewed_by) REFERENCES users (id) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '加入申请';

-- -----------------------------------------------------------------------------
-- 3.8 posts：帖子
-- -----------------------------------------------------------------------------
CREATE TABLE posts (
    id             CHAR(26)      NOT NULL COMMENT 'ULID，字典序即发布时间序',
    channel_id     CHAR(26)      NOT NULL,
    board_id       CHAR(26)      NOT NULL,
    author_id      CHAR(26)      NOT NULL,
    title          VARCHAR(200)  NULL COMMENT '长文标题',
    content        MEDIUMTEXT    NOT NULL COMMENT '正文（PLAIN 或净化后的 HTML）',
    content_format VARCHAR(16)   NOT NULL DEFAULT 'PLAIN' COMMENT 'PLAIN/HTML',
    cover_url      VARCHAR(500)  NULL,
    view_count     BIGINT        NOT NULL DEFAULT 0,
    like_count     BIGINT        NOT NULL DEFAULT 0,
    comment_count  BIGINT        NOT NULL DEFAULT 0,
    share_count    BIGINT        NOT NULL DEFAULT 0,
    is_pinned      TINYINT(1)    NOT NULL DEFAULT 0,
    is_essence     TINYINT(1)    NOT NULL DEFAULT 0,
    pinned_at      DATETIME(3)   NULL,
    status         VARCHAR(16)   NOT NULL DEFAULT 'PUBLISHED' COMMENT 'PUBLISHED/DELETED/HIDDEN',
    created_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at     DATETIME(3)   NULL,
    PRIMARY KEY (id),
    KEY idx_posts_channel_created (channel_id, status, created_at),
    KEY idx_posts_channel_board_created (channel_id, board_id, status, created_at),
    KEY idx_posts_author (author_id),
    KEY idx_posts_pinned (channel_id, is_pinned, pinned_at),
    CONSTRAINT fk_posts_channel FOREIGN KEY (channel_id) REFERENCES channels (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_posts_board FOREIGN KEY (board_id) REFERENCES boards (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_posts_author FOREIGN KEY (author_id) REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '帖子';

-- -----------------------------------------------------------------------------
-- 3.9 post_media：帖子媒体
-- -----------------------------------------------------------------------------
CREATE TABLE post_media (
    id          CHAR(26)     NOT NULL,
    post_id     CHAR(26)     NOT NULL,
    media_type  VARCHAR(16)  NOT NULL COMMENT 'IMAGE/VIDEO',
    url         VARCHAR(500) NOT NULL,
    thumb_url   VARCHAR(500) NULL,
    width       INT          NULL,
    height      INT          NULL,
    duration_ms BIGINT       NULL,
    sort        INT          NOT NULL DEFAULT 0,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_post_media_post (post_id, sort),
    CONSTRAINT fk_post_media_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '帖子媒体';

-- -----------------------------------------------------------------------------
-- 3.10 comments：评论
-- -----------------------------------------------------------------------------
CREATE TABLE comments (
    id               CHAR(26)    NOT NULL,
    post_id          CHAR(26)    NOT NULL,
    author_id        CHAR(26)    NOT NULL,
    parent_id        CHAR(26)    NULL COMMENT '顶级评论为空；回复指向顶级评论',
    reply_to_user_id CHAR(26)    NULL,
    content          TEXT        NOT NULL,
    media            JSON        NULL COMMENT '[{type,url,thumbUrl}]',
    emoji            JSON        NULL,
    like_count       BIGINT      NOT NULL DEFAULT 0,
    status           VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED' COMMENT 'PUBLISHED/DELETED/HIDDEN',
    created_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    deleted_at       DATETIME(3) NULL,
    PRIMARY KEY (id),
    KEY idx_comments_post_created (post_id, status, created_at),
    KEY idx_comments_post_likes (post_id, status, like_count),
    KEY idx_comments_parent (parent_id),
    CONSTRAINT fk_comments_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_comments_author FOREIGN KEY (author_id) REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_comments_parent FOREIGN KEY (parent_id) REFERENCES comments (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_comments_reply_to FOREIGN KEY (reply_to_user_id) REFERENCES users (id) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '评论';

-- -----------------------------------------------------------------------------
-- 3.11 post_likes / comment_likes：点赞（唯一约束防重复）
-- -----------------------------------------------------------------------------
CREATE TABLE post_likes (
    id         CHAR(26)    NOT NULL,
    post_id    CHAR(26)    NOT NULL,
    user_id    CHAR(26)    NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_post_likes_post_user (post_id, user_id),
    KEY idx_post_likes_user (user_id),
    CONSTRAINT fk_post_likes_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_post_likes_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '帖子点赞';

CREATE TABLE comment_likes (
    id         CHAR(26)    NOT NULL,
    comment_id CHAR(26)    NOT NULL,
    user_id    CHAR(26)    NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_comment_likes_comment_user (comment_id, user_id),
    KEY idx_comment_likes_user (user_id),
    CONSTRAINT fk_comment_likes_comment FOREIGN KEY (comment_id) REFERENCES comments (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_comment_likes_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '评论点赞';

-- -----------------------------------------------------------------------------
-- 3.11 notifications：通知
-- -----------------------------------------------------------------------------
CREATE TABLE notifications (
    id         CHAR(26)     NOT NULL,
    user_id    CHAR(26)     NOT NULL,
    type       VARCHAR(32)  NOT NULL COMMENT 'POST_LIKED/POST_COMMENTED/COMMENT_REPLIED/SYSTEM',
    channel_id CHAR(26)     NULL,
    post_id    CHAR(26)     NULL,
    comment_id CHAR(26)     NULL,
    actor_id   CHAR(26)     NULL,
    title      VARCHAR(200) NULL,
    content    VARCHAR(500) NULL,
    is_read    TINYINT(1)   NOT NULL DEFAULT 0,
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_notifications_user_read_created (user_id, is_read, created_at),
    KEY idx_notifications_user_id (user_id, id),
    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_notifications_channel FOREIGN KEY (channel_id) REFERENCES channels (id) ON DELETE SET NULL ON UPDATE RESTRICT,
    CONSTRAINT fk_notifications_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE SET NULL ON UPDATE RESTRICT,
    CONSTRAINT fk_notifications_comment FOREIGN KEY (comment_id) REFERENCES comments (id) ON DELETE SET NULL ON UPDATE RESTRICT,
    CONSTRAINT fk_notifications_actor FOREIGN KEY (actor_id) REFERENCES users (id) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '通知';

-- -----------------------------------------------------------------------------
-- 3.12 search_history / search_logs：搜索历史与日志
-- -----------------------------------------------------------------------------
CREATE TABLE search_history (
    id         CHAR(26)     NOT NULL,
    user_id    CHAR(26)     NOT NULL,
    keyword    VARCHAR(100) NOT NULL,
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_search_history_user_keyword (user_id, keyword),
    KEY idx_search_history_user_created (user_id, created_at),
    CONSTRAINT fk_search_history_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '搜索历史';

CREATE TABLE search_logs (
    id           CHAR(26)     NOT NULL,
    user_id      CHAR(26)     NULL,
    keyword      VARCHAR(100) NOT NULL,
    scope        VARCHAR(16)  NOT NULL DEFAULT 'GLOBAL' COMMENT 'GLOBAL/CHANNEL',
    channel_id   CHAR(26)     NULL,
    result_count INT          NOT NULL DEFAULT 0,
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_search_logs_keyword (keyword(32)),
    KEY idx_search_logs_created (created_at),
    CONSTRAINT fk_search_logs_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '搜索日志';

-- -----------------------------------------------------------------------------
-- 3.12 media_files：上传媒体
-- -----------------------------------------------------------------------------
CREATE TABLE media_files (
    id           CHAR(26)     NOT NULL,
    uploader_id  CHAR(26)     NOT NULL,
    media_type   VARCHAR(16)  NOT NULL COMMENT 'IMAGE/VIDEO',
    storage_path VARCHAR(500) NOT NULL,
    url          VARCHAR(500) NOT NULL,
    thumb_url    VARCHAR(500) NULL,
    size_bytes   BIGINT       NOT NULL DEFAULT 0,
    width        INT          NULL,
    height       INT          NULL,
    duration_ms  BIGINT       NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DELETED',
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_media_files_uploader (uploader_id),
    CONSTRAINT fk_media_files_uploader FOREIGN KEY (uploader_id) REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '上传媒体文件';
