-- =============================================================================
-- V10__channel_apps_voice_and_activities.sql
-- 频道应用（语音房/直播间/自定义）、语音房与麦位、频道活动与报名
--
-- 依据：docs/11 §5.6、docs/06 §13、docs/05 §2.14
-- 约定：ULID CHAR(26) 主键、utf8mb4 / utf8mb4_0900_ai_ci、DATETIME(3) 存 UTC、
--       外键/唯一键/索引显式命名（fk_/uk_/idx_），迁移只追加不回改。
-- =============================================================================

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- 1. channel_apps：频道应用入口（语音房 / 直播间 / 自定义）
--    sort_order 用于频道页排序；enabled=false 时前台隐藏但保留配置。
-- -----------------------------------------------------------------------------
CREATE TABLE channel_apps (
    id          CHAR(26)     NOT NULL COMMENT 'ULID',
    channel_id  CHAR(26)     NOT NULL,
    app_type    VARCHAR(16)  NOT NULL COMMENT 'VOICE_ROOM / LIVE_ROOM / CUSTOM',
    name        VARCHAR(32)  NOT NULL,
    icon_url    VARCHAR(500) NULL,
    description VARCHAR(200) NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    enabled     TINYINT(1)   NOT NULL DEFAULT 1,
    created_by  CHAR(26)     NULL,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_channel_apps_channel_sort (channel_id, sort_order, id),
    CONSTRAINT fk_channel_apps_channel FOREIGN KEY (channel_id) REFERENCES channels (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_channel_apps_creator FOREIGN KEY (created_by) REFERENCES users (id)
        ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '频道应用入口';

-- -----------------------------------------------------------------------------
-- 2. voice_rooms：语音房
--    status: OPEN / CLOSED；closed_at 记录关房时间（保留历史房间）。
--    app_id 为空表示「未挂到某个应用入口」的临时房间；应用删除时置空。
-- -----------------------------------------------------------------------------
CREATE TABLE voice_rooms (
    id         CHAR(26)    NOT NULL COMMENT 'ULID',
    channel_id CHAR(26)    NOT NULL,
    app_id     CHAR(26)    NULL,
    host_id    CHAR(26)    NOT NULL COMMENT '房主',
    title      VARCHAR(60) NOT NULL,
    status     VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN / CLOSED',
    max_seats  INT         NOT NULL DEFAULT 8 COMMENT '麦位数量（1-20）',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    closed_at  DATETIME(3) NULL,
    PRIMARY KEY (id),
    KEY idx_voice_rooms_channel_status (channel_id, status, created_at),
    KEY idx_voice_rooms_app (app_id),
    CONSTRAINT fk_voice_rooms_channel FOREIGN KEY (channel_id) REFERENCES channels (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_voice_rooms_app FOREIGN KEY (app_id) REFERENCES channel_apps (id)
        ON DELETE SET NULL ON UPDATE RESTRICT,
    CONSTRAINT fk_voice_rooms_host FOREIGN KEY (host_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '语音房';

-- -----------------------------------------------------------------------------
-- 3. voice_room_seats：麦位（一个房间内一个用户只能在一个麦位）
--    唯一键 uk_voice_room_seats_room_seat 保证「抢座」在数据库层被兜底：
--    并发上麦时只有一条 INSERT 成功，另一条抛 DuplicateKey。
-- -----------------------------------------------------------------------------
CREATE TABLE voice_room_seats (
    id         CHAR(26)    NOT NULL COMMENT 'ULID',
    room_id    CHAR(26)    NOT NULL,
    user_id    CHAR(26)    NOT NULL,
    seat_index INT         NOT NULL COMMENT '0 起，< voice_rooms.max_seats',
    muted      TINYINT(1)  NOT NULL DEFAULT 0,
    joined_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_voice_room_seats_room_user (room_id, user_id),
    UNIQUE KEY uk_voice_room_seats_room_seat (room_id, seat_index),
    KEY idx_voice_room_seats_user (user_id, joined_at),
    CONSTRAINT fk_voice_room_seats_room FOREIGN KEY (room_id) REFERENCES voice_rooms (id)
        ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_voice_room_seats_user FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '语音房麦位';

-- -----------------------------------------------------------------------------
-- 4. channel_activities：频道活动
--    status: DRAFT / PUBLISHED / CANCELLED；reminder_sent_at 用于「开始前 30 分钟提醒」幂等。
-- -----------------------------------------------------------------------------
CREATE TABLE channel_activities (
    id                CHAR(26)     NOT NULL COMMENT 'ULID',
    channel_id        CHAR(26)     NOT NULL,
    title             VARCHAR(100) NOT NULL,
    description       VARCHAR(1000) NULL,
    start_at          DATETIME(3)  NOT NULL,
    end_at            DATETIME(3)  NULL,
    location          VARCHAR(200) NULL,
    capacity          INT          NULL COMMENT '报名上限，空=不限',
    status            VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / PUBLISHED / CANCELLED',
    reminder_sent_at  DATETIME(3)  NULL COMMENT '提醒发送时间（幂等标记）',
    created_by        CHAR(26)     NULL,
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_channel_activities_channel_start (channel_id, status, start_at),
    KEY idx_channel_activities_reminder (status, start_at, reminder_sent_at),
    CONSTRAINT fk_channel_activities_channel FOREIGN KEY (channel_id) REFERENCES channels (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_channel_activities_creator FOREIGN KEY (created_by) REFERENCES users (id)
        ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '频道活动';

-- -----------------------------------------------------------------------------
-- 5. activity_signups：活动报名（一个用户一条，取消即改状态，便于恢复与统计）
-- -----------------------------------------------------------------------------
CREATE TABLE activity_signups (
    id          CHAR(26)    NOT NULL COMMENT 'ULID',
    activity_id CHAR(26)    NOT NULL,
    user_id     CHAR(26)    NOT NULL,
    status      VARCHAR(16) NOT NULL DEFAULT 'GOING' COMMENT 'GOING / CANCELLED',
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_activity_signups_activity_user (activity_id, user_id),
    KEY idx_activity_signups_activity_status (activity_id, status),
    KEY idx_activity_signups_user (user_id, created_at),
    CONSTRAINT fk_activity_signups_activity FOREIGN KEY (activity_id) REFERENCES channel_activities (id)
        ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_activity_signups_user FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '活动报名';
