-- =============================================================================
-- 群岛（Isles）WP2：好友关系 + 私信 + 实时推送配套存储
-- 依据：docs/03 §4.12（FR-DM-01~05）、§4.1（FR-USER-05）、§11 决策 5/11；
--       docs/02 §5.4（STOMP 实时通道）、§7（数据域：社交 friend_relations / dm_*）；
--       docs/06 §4（M2 预留表）。本文件为 V4，只追加、不回改；
--       不新增/修改 users、channels、posts 等既有表字段。
-- 约定：ULID CHAR(26) 主键、utf8mb4 / utf8mb4_0900_ai_ci、DATETIME(3) 存 UTC、
--       外键/唯一约束/索引显式命名（fk_/uk_/idx_）。
-- 回滚/补偿：本迁移只新增表，回滚时按 dm_messages → dm_conversations → friend_relations
--       顺序 DROP 即可；业务代码不依赖历史数据即可安全回退。
-- =============================================================================

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- friend_relations：好友关系（有向行，双向好友 = 两条 ACCEPTED 行）
-- 状态：PENDING（申请中）/ ACCEPTED（好友）/ REJECTED（已拒绝）/ BLOCKED（拉黑）
-- 查询服务：
--   * 好友列表：user_id = ? AND status = 'ACCEPTED' AND id < ? ORDER BY id DESC
--   * 待处理申请：target_user_id = ? AND status = 'PENDING'
--   * 拉黑判定：user_id = ? AND target_user_id = ? AND status = 'BLOCKED'
-- -----------------------------------------------------------------------------
CREATE TABLE friend_relations (
    id             CHAR(26)    NOT NULL COMMENT 'ULID',
    user_id        CHAR(26)    NOT NULL COMMENT '主体用户（申请方 / 拉黑方）',
    target_user_id CHAR(26)    NOT NULL COMMENT '对象用户（被申请方 / 被拉黑方）',
    status         VARCHAR(16) NOT NULL COMMENT 'PENDING/ACCEPTED/REJECTED/BLOCKED',
    created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_friend_relations_user_target (user_id, target_user_id),
    KEY idx_friend_relations_user_status (user_id, status, id),
    KEY idx_friend_relations_target_status (target_user_id, status, id),
    CONSTRAINT fk_friend_relations_user FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_friend_relations_target FOREIGN KEY (target_user_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '好友关系（申请/好友/拉黑）';

-- -----------------------------------------------------------------------------
-- dm_conversations：一对一会话（参与者 + 最后一条消息 + 双侧未读计数）
-- 不变量：user_low_id < user_high_id（字典序），唯一约束保证同一对用户只有一个会话。
-- 查询服务：
--   * 会话列表：user_low_id = ? OR user_high_id = ?，按 last_message_at DESC 游标分页
--   * 未读总数：按当前用户所在侧累加 unread_low / unread_high
-- -----------------------------------------------------------------------------
CREATE TABLE dm_conversations (
    id                     CHAR(26)     NOT NULL COMMENT 'ULID',
    user_low_id            CHAR(26)     NOT NULL COMMENT '参与者（ID 字典序较小者）',
    user_high_id           CHAR(26)     NOT NULL COMMENT '参与者（ID 字典序较大者）',
    last_message_id        CHAR(26)     NULL COMMENT '最后一条消息（冗余指针，不建外键避免循环依赖）',
    last_message_sender_id CHAR(26)     NULL,
    last_message_type      VARCHAR(16)  NULL COMMENT 'TEXT/IMAGE/POST',
    last_message_preview   VARCHAR(200) NULL COMMENT '会话列表预览文本',
    last_message_at        DATETIME(3)  NOT NULL COMMENT '会话活跃时间（创建时=创建时间），会话列表排序键',
    unread_low             BIGINT       NOT NULL DEFAULT 0 COMMENT 'user_low_id 侧未读数',
    unread_high            BIGINT       NOT NULL DEFAULT 0 COMMENT 'user_high_id 侧未读数',
    created_at             DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at             DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_dm_conversations_pair (user_low_id, user_high_id),
    KEY idx_dm_conversations_low_activity (user_low_id, last_message_at, id),
    KEY idx_dm_conversations_high_activity (user_high_id, last_message_at, id),
    CONSTRAINT fk_dm_conversations_low FOREIGN KEY (user_low_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_dm_conversations_high FOREIGN KEY (user_high_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '私信会话';

-- -----------------------------------------------------------------------------
-- dm_messages：私信消息（TEXT 文本 / IMAGE 图片 / POST 帖子分享卡片）
-- 查询服务：
--   * 历史消息：conversation_id = ? AND id < ? ORDER BY id DESC（ULID 时间序即游标）
--   * 未读已读：conversation_id = ? AND receiver_id = ? AND is_read = 0
--   * 重试幂等：uk_dm_messages_client 保证同一会话同一发送方 client_message_id 唯一
--     （client_message_id 为 NULL 时 MySQL 唯一索引允许多行，兼容客户端不传的情况）
-- -----------------------------------------------------------------------------
CREATE TABLE dm_messages (
    id                CHAR(26)     NOT NULL COMMENT 'ULID（字典序即发送时间序，作历史游标）',
    conversation_id   CHAR(26)     NOT NULL,
    sender_id         CHAR(26)     NOT NULL,
    receiver_id       CHAR(26)     NOT NULL,
    type              VARCHAR(16)  NOT NULL COMMENT 'TEXT/IMAGE/POST',
    content           TEXT         NULL COMMENT 'TEXT 正文 / 图片说明 / 分享附带文本',
    payload           JSON         NULL COMMENT 'IMAGE: {url,thumbUrl}；POST: {postId,title,channelId,channelName,coverUrl}',
    client_message_id VARCHAR(64)  NULL COMMENT '客户端消息 ID，用于发送重试去重',
    is_read           TINYINT(1)   NOT NULL DEFAULT 0,
    read_at           DATETIME(3)  NULL,
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_dm_messages_client (conversation_id, sender_id, client_message_id),
    KEY idx_dm_messages_conversation_id (conversation_id, id),
    KEY idx_dm_messages_conversation_created (conversation_id, created_at),
    KEY idx_dm_messages_unread (conversation_id, receiver_id, is_read),
    KEY idx_dm_messages_sender (sender_id),
    CONSTRAINT fk_dm_messages_conversation FOREIGN KEY (conversation_id) REFERENCES dm_conversations (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_dm_messages_sender FOREIGN KEY (sender_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_dm_messages_receiver FOREIGN KEY (receiver_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '私信消息';
