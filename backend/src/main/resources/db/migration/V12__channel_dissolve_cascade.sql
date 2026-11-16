-- =============================================================================
-- V12__channel_dissolve_cascade.sql
-- 频道解散后清理 V10 引入的三张频道级数据表，并补 ON DELETE CASCADE 兜底
--
-- 背景（K2）：channel_apps / voice_rooms / channel_activities 通过外键指向 channels，
-- 但「解散频道」是**软删除**（status=DISSOLVED + deleted_at），频道行仍在，
-- 所以单靠 ON DELETE CASCADE 永远不会触发——解散后这些行会一直留着（孤儿行）。
--
-- 结论（二选一，这里两个都做，主次分明）：
--   1) 【主】服务层显式清理：ChannelSettingsService#dissolveChannel 调
--      ChannelAppDataCleaner#purgeChannel，删除应用/房间/麦位/活动/报名。
--      这是唯一能覆盖「软删除」的路径，也是本轮修复 bug 的实际手段。
--   2) 【兜底】本迁移把三张表指向 channels 的外键改为 ON DELETE CASCADE，
--      用于将来出现物理删除（平台清理、测试库重建等）时不留垃圾。
--
-- 迁移同时清理历史遗留：频道已 DISSOLVED 或频道行已不存在的行。
-- =============================================================================

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- 1. 清理历史孤儿数据（按依赖顺序：报名 → 活动 → 麦位 → 房间 → 应用）
-- -----------------------------------------------------------------------------

-- 1.1 报名：所属活动已不存在，或活动所属频道已解散/不存在
DELETE s FROM activity_signups s
    LEFT JOIN channel_activities a ON a.id = s.activity_id
    LEFT JOIN channels c ON c.id = a.channel_id
WHERE a.id IS NULL
   OR c.id IS NULL
   OR c.status = 'DISSOLVED'
   OR c.deleted_at IS NOT NULL;

-- 1.2 活动：所属频道已解散/不存在
DELETE a FROM channel_activities a
    LEFT JOIN channels c ON c.id = a.channel_id
WHERE c.id IS NULL
   OR c.status = 'DISSOLVED'
   OR c.deleted_at IS NOT NULL;

-- 1.3 麦位：所属房间已不存在，或房间所属频道已解散/不存在
DELETE s FROM voice_room_seats s
    LEFT JOIN voice_rooms r ON r.id = s.room_id
    LEFT JOIN channels c ON c.id = r.channel_id
WHERE r.id IS NULL
   OR c.id IS NULL
   OR c.status = 'DISSOLVED'
   OR c.deleted_at IS NOT NULL;

-- 1.4 语音房：所属频道已解散/不存在
DELETE r FROM voice_rooms r
    LEFT JOIN channels c ON c.id = r.channel_id
WHERE c.id IS NULL
   OR c.status = 'DISSOLVED'
   OR c.deleted_at IS NOT NULL;

-- 1.5 应用：所属频道已解散/不存在
DELETE a FROM channel_apps a
    LEFT JOIN channels c ON c.id = a.channel_id
WHERE c.id IS NULL
   OR c.status = 'DISSOLVED'
   OR c.deleted_at IS NOT NULL;

-- -----------------------------------------------------------------------------
-- 2. 外键改为 ON DELETE CASCADE（先删旧外键，再建同名新外键）
-- -----------------------------------------------------------------------------

ALTER TABLE channel_apps DROP FOREIGN KEY fk_channel_apps_channel;
ALTER TABLE channel_apps
    ADD CONSTRAINT fk_channel_apps_channel FOREIGN KEY (channel_id) REFERENCES channels (id)
        ON DELETE CASCADE ON UPDATE RESTRICT;

ALTER TABLE voice_rooms DROP FOREIGN KEY fk_voice_rooms_channel;
ALTER TABLE voice_rooms
    ADD CONSTRAINT fk_voice_rooms_channel FOREIGN KEY (channel_id) REFERENCES channels (id)
        ON DELETE CASCADE ON UPDATE RESTRICT;

ALTER TABLE channel_activities DROP FOREIGN KEY fk_channel_activities_channel;
ALTER TABLE channel_activities
    ADD CONSTRAINT fk_channel_activities_channel FOREIGN KEY (channel_id) REFERENCES channels (id)
        ON DELETE CASCADE ON UPDATE RESTRICT;
