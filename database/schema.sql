-- =====================================================================
-- 群岛（Isles）数据库结构（仅结构，不含任何数据）
--
-- 适用：MySQL 8.0+（utf8mb4 / utf8mb4_0900_ai_ci）
-- 生成：mysqldump --no-data（V1–V9 迁移全部应用后的结构）
-- 表数：39 张（不含 Flyway 的 flyway_schema_history）
--       V1–V4：核心/管理/AI/社交；V5：思考模式；V6：平台后台；V7：公告与敏感词；
--       V8：频道助手可选模型 + AI 工具调用开关；V9：任务日志记录模型（均只加列，不新增表）
--
-- 两种初始化方式（二选一）：
--   A. 推荐：只建空库，启动后端由 Flyway 自动执行 V1–V9 迁移
--        CREATE DATABASE my_social_channel DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
--   B. 手工导入本文件（表已建好），启动后端时必须关闭 Flyway，否则会重复建表报错：
--        CREATE DATABASE my_social_channel DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
--        mysql -u root -p my_social_channel < database/schema.sql
--        # 启动时设置 SPRING_FLYWAY_ENABLED=false
--
-- 注意：本文件由 mysqldump 生成，包含 DROP TABLE IF EXISTS，导入前请确认目标库为空。
-- =====================================================================

-- MySQL dump 10.13  Distrib 8.0.45, for Win64 (x86_64)
--
-- Host: 127.0.0.1    Database: my_social_channel
-- ------------------------------------------------------
-- Server version	8.0.45

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Table structure for table `ai_conversations`
--

DROP TABLE IF EXISTS `ai_conversations`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ai_conversations` (
  `id` char(26) NOT NULL,
  `user_id` char(26) NOT NULL,
  `scope` varchar(16) NOT NULL DEFAULT 'GLOBAL' COMMENT 'GLOBAL / CHANNEL',
  `channel_id` char(26) DEFAULT NULL COMMENT 'scope=CHANNEL 时的频道',
  `title` varchar(120) DEFAULT NULL,
  `model_config_id` char(26) DEFAULT NULL COMMENT '最近使用的模型配置',
  `message_count` int NOT NULL DEFAULT '0',
  `last_message_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `deleted_at` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_ai_conversations_user` (`user_id`,`deleted_at`,`updated_at`),
  KEY `idx_ai_conversations_channel` (`channel_id`,`scope`),
  KEY `fk_ai_conversations_model` (`model_config_id`),
  CONSTRAINT `fk_ai_conversations_channel` FOREIGN KEY (`channel_id`) REFERENCES `channels` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `fk_ai_conversations_model` FOREIGN KEY (`model_config_id`) REFERENCES `ai_model_configs` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `fk_ai_conversations_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 会话';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `ai_messages`
--

DROP TABLE IF EXISTS `ai_messages`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ai_messages` (
  `id` char(26) NOT NULL,
  `conversation_id` char(26) NOT NULL,
  `user_id` char(26) DEFAULT NULL COMMENT '提问用户（助手回答也记录，便于配额统计）',
  `role` varchar(16) NOT NULL COMMENT 'USER/ASSISTANT/SYSTEM',
  `content` mediumtext NOT NULL,
  `reasoning_content` mediumtext COMMENT '深度思考过程（可选）',
  `images` json DEFAULT NULL COMMENT '用户上传图片 [{url}]',
  `sources` json DEFAULT NULL COMMENT '回答引用的帖子 [{id,title,channelId,channelName,excerpt,url}]',
  `model` varchar(128) DEFAULT NULL COMMENT '生成时使用的模型 ID',
  `model_config_id` char(26) DEFAULT NULL,
  `token_in` int DEFAULT NULL,
  `token_out` int DEFAULT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'COMPLETED' COMMENT 'COMPLETED/STREAMING/ABORTED/FAILED',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_ai_messages_conversation` (`conversation_id`,`created_at`),
  KEY `idx_ai_messages_user_created` (`user_id`,`created_at`),
  KEY `fk_ai_messages_model` (`model_config_id`),
  CONSTRAINT `fk_ai_messages_conversation` FOREIGN KEY (`conversation_id`) REFERENCES `ai_conversations` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_ai_messages_model` FOREIGN KEY (`model_config_id`) REFERENCES `ai_model_configs` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `fk_ai_messages_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 消息';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `ai_model_configs`
--

DROP TABLE IF EXISTS `ai_model_configs`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ai_model_configs` (
  `id` char(26) NOT NULL,
  `name` varchar(64) NOT NULL COMMENT '配置名称（展示用，唯一）',
  `base_url` varchar(500) NOT NULL COMMENT 'OpenAI 兼容 Base URL，如 http://localhost:1234/v1',
  `api_key` varchar(1000) DEFAULT NULL COMMENT 'API Key 密文（或未配置密钥时的明文）',
  `api_key_encrypted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '1=AES-GCM 密文',
  `model` varchar(128) NOT NULL COMMENT '模型 ID，如 qwen2.5-vl-7b',
  `vision_enabled` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否支持图片（多模态）',
  `reasoning_enabled` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否支持深度思考/推理参数',
  `thinking_mode` varchar(32) NOT NULL DEFAULT 'REASONING_EFFORT' COMMENT '思考参数下发方式：REASONING_EFFORT / CHAT_TEMPLATE / BOTH / NONE',
  `tool_call_enabled` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否启用工具调用（function calling）；关闭时使用关键词检索',
  `is_default` tinyint(1) NOT NULL DEFAULT '0' COMMENT '默认模型',
  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '启用状态',
  `timeout_seconds` int NOT NULL DEFAULT '60' COMMENT '请求超时（秒）',
  `max_tokens` int NOT NULL DEFAULT '2048' COMMENT '最大输出 token',
  `created_by` char(26) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_model_configs_name` (`name`),
  KEY `idx_ai_model_configs_enabled` (`enabled`,`is_default`),
  KEY `fk_ai_model_configs_creator` (`created_by`),
  CONSTRAINT `fk_ai_model_configs_creator` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 模型配置';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `ai_task_logs`
--

DROP TABLE IF EXISTS `ai_task_logs`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ai_task_logs` (
  `id` char(26) NOT NULL,
  `channel_id` char(26) NOT NULL,
  `setting_id` char(26) DEFAULT NULL,
  `task_type` varchar(32) NOT NULL,
  `frequency_key` varchar(40) NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'RUNNING',
  `result_text` text COMMENT '生成内容摘要',
  `result` json DEFAULT NULL COMMENT '结构化结果（如贡献榜条目）',
  `post_id` char(26) DEFAULT NULL,
  `comment_id` char(26) DEFAULT NULL,
  `error_message` varchar(1000) DEFAULT NULL,
  `model_config_id` char(26) DEFAULT NULL COMMENT '本次使用的模型配置；配置删除后置空',
  `model` varchar(128) DEFAULT NULL COMMENT '模型 ID 快照（来自 ai_model_configs.model）',
  `started_at` datetime(3) DEFAULT NULL,
  `finished_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_task_logs_channel_type_key` (`channel_id`,`task_type`,`frequency_key`),
  KEY `idx_ai_task_logs_channel_created` (`channel_id`,`created_at`),
  KEY `idx_ai_task_logs_status` (`status`,`created_at`),
  KEY `fk_ai_task_logs_setting` (`setting_id`),
  KEY `fk_ai_task_logs_post` (`post_id`),
  KEY `fk_ai_task_logs_comment` (`comment_id`),
  KEY `idx_ai_task_logs_model_config` (`model_config_id`),
  CONSTRAINT `fk_ai_task_logs_channel` FOREIGN KEY (`channel_id`) REFERENCES `channels` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_ai_task_logs_comment` FOREIGN KEY (`comment_id`) REFERENCES `comments` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `fk_ai_task_logs_model_config` FOREIGN KEY (`model_config_id`) REFERENCES `ai_model_configs` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `fk_ai_task_logs_post` FOREIGN KEY (`post_id`) REFERENCES `posts` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `fk_ai_task_logs_setting` FOREIGN KEY (`setting_id`) REFERENCES `channel_ai_settings` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='频道助手任务执行日志';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `announcements`
--

DROP TABLE IF EXISTS `announcements`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `announcements` (
  `id` char(26) NOT NULL COMMENT 'ULID',
  `title` varchar(120) NOT NULL,
  `content` varchar(1000) NOT NULL COMMENT '纯文本；前台按文本渲染',
  `level` varchar(16) NOT NULL DEFAULT 'INFO' COMMENT 'INFO/WARNING/CRITICAL',
  `placement` varchar(16) NOT NULL DEFAULT 'BANNER' COMMENT 'BANNER/MODAL/INBOX',
  `status` varchar(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PUBLISHED/ARCHIVED',
  `start_at` datetime(3) DEFAULT NULL COMMENT '生效开始；空=立即',
  `end_at` datetime(3) DEFAULT NULL COMMENT '生效结束；空=长期',
  `created_by` char(26) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_announcements_status_window` (`status`,`start_at`,`end_at`),
  KEY `idx_announcements_created` (`created_at`),
  KEY `fk_announcements_created_by` (`created_by`),
  CONSTRAINT `fk_announcements_created_by` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台公告';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `boards`
--

DROP TABLE IF EXISTS `boards`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `boards` (
  `id` char(26) NOT NULL,
  `channel_id` char(26) NOT NULL,
  `name` varchar(32) NOT NULL,
  `description` varchar(200) DEFAULT NULL,
  `sort` int NOT NULL DEFAULT '0',
  `post_count` bigint NOT NULL DEFAULT '0',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `deleted_at` datetime(3) DEFAULT NULL COMMENT '软删除，查询默认过滤',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_boards_channel_name` (`channel_id`,`name`),
  KEY `idx_boards_channel_sort` (`channel_id`,`sort`),
  KEY `idx_boards_channel_deleted` (`channel_id`,`deleted_at`),
  CONSTRAINT `fk_boards_channel` FOREIGN KEY (`channel_id`) REFERENCES `channels` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='频道板块';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `channel_ai_settings`
--

DROP TABLE IF EXISTS `channel_ai_settings`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `channel_ai_settings` (
  `id` char(26) NOT NULL,
  `channel_id` char(26) NOT NULL,
  `assistant_user_id` char(26) DEFAULT NULL COMMENT '助手虚拟账号',
  `nickname` varchar(32) NOT NULL DEFAULT '频道助手',
  `avatar_url` varchar(500) DEFAULT NULL,
  `cover_url` varchar(500) DEFAULT NULL,
  `bio` varchar(500) DEFAULT NULL,
  `persona` varchar(2000) DEFAULT NULL COMMENT '助手设定/性格描述',
  `model_config_id` char(26) DEFAULT NULL COMMENT '频道助手使用的模型配置；空=跟随平台默认',
  `enabled` tinyint(1) NOT NULL DEFAULT '0' COMMENT '频道助手总开关',
  `summary_daily_enabled` tinyint(1) NOT NULL DEFAULT '0',
  `summary_weekly_enabled` tinyint(1) NOT NULL DEFAULT '0',
  `monthly_contributors_enabled` tinyint(1) NOT NULL DEFAULT '0',
  `auto_post_enabled` tinyint(1) NOT NULL DEFAULT '0',
  `auto_comment_enabled` tinyint(1) NOT NULL DEFAULT '0',
  `auto_like_enabled` tinyint(1) NOT NULL DEFAULT '0',
  `auto_reply_enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '有人回复助手评论时自动接话',
  `daily_summary_hour` tinyint NOT NULL DEFAULT '9' COMMENT 'UTC 小时（0-23）',
  `auto_post_per_day` int NOT NULL DEFAULT '1',
  `auto_comment_per_day` int NOT NULL DEFAULT '3',
  `auto_like_per_day` int NOT NULL DEFAULT '10',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_channel_ai_settings_channel` (`channel_id`),
  KEY `fk_channel_ai_settings_assistant` (`assistant_user_id`),
  KEY `idx_channel_ai_settings_model_config` (`model_config_id`),
  CONSTRAINT `fk_channel_ai_settings_assistant` FOREIGN KEY (`assistant_user_id`) REFERENCES `users` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `fk_channel_ai_settings_channel` FOREIGN KEY (`channel_id`) REFERENCES `channels` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_channel_ai_settings_model_config` FOREIGN KEY (`model_config_id`) REFERENCES `ai_model_configs` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='频道助手设置';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `channel_audit_logs`
--

DROP TABLE IF EXISTS `channel_audit_logs`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `channel_audit_logs` (
  `id` char(26) NOT NULL COMMENT 'ULID',
  `channel_id` char(26) NOT NULL,
  `actor_id` char(26) NOT NULL COMMENT '操作者',
  `action` varchar(64) NOT NULL COMMENT '动作，如 channel.settings / post.pin / member.mute',
  `target_type` varchar(32) DEFAULT NULL COMMENT 'POST/COMMENT/MEMBER/ROLE/BOARD/INVITE/JOIN_REQUEST/REPORT/CHANNEL',
  `target_id` char(26) DEFAULT NULL,
  `detail` json DEFAULT NULL COMMENT '操作明细（变更前后、原因等）',
  `ip` varchar(45) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_channel_audit_logs_channel_created` (`channel_id`,`created_at`),
  KEY `idx_channel_audit_logs_actor_created` (`actor_id`,`created_at`),
  KEY `idx_channel_audit_logs_action` (`action`),
  CONSTRAINT `fk_channel_audit_logs_actor` FOREIGN KEY (`actor_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_channel_audit_logs_channel` FOREIGN KEY (`channel_id`) REFERENCES `channels` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='频道管理日志';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `channel_invites`
--

DROP TABLE IF EXISTS `channel_invites`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `channel_invites` (
  `id` char(26) NOT NULL,
  `channel_id` char(26) NOT NULL,
  `token_hash` char(64) NOT NULL COMMENT 'SHA-256 十六进制',
  `requires_approval` tinyint(1) NOT NULL DEFAULT '0',
  `expires_at` datetime(3) DEFAULT NULL,
  `max_uses` int DEFAULT NULL,
  `used_count` int NOT NULL DEFAULT '0',
  `created_by` char(26) NOT NULL,
  `revoked_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_channel_invites_token` (`token_hash`),
  KEY `idx_channel_invites_channel` (`channel_id`),
  KEY `fk_channel_invites_creator` (`created_by`),
  CONSTRAINT `fk_channel_invites_channel` FOREIGN KEY (`channel_id`) REFERENCES `channels` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_channel_invites_creator` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='频道邀请链接';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `channel_members`
--

DROP TABLE IF EXISTS `channel_members`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `channel_members` (
  `id` char(26) NOT NULL,
  `channel_id` char(26) NOT NULL,
  `user_id` char(26) NOT NULL,
  `role_id` char(26) NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/MUTED/LEFT/KICKED',
  `muted_until` datetime(3) DEFAULT NULL COMMENT '个人禁言到期时间',
  `joined_at` datetime(3) NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_channel_members_channel_user` (`channel_id`,`user_id`),
  KEY `idx_channel_members_user` (`user_id`),
  KEY `idx_channel_members_role` (`role_id`),
  CONSTRAINT `fk_channel_members_channel` FOREIGN KEY (`channel_id`) REFERENCES `channels` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_channel_members_role` FOREIGN KEY (`role_id`) REFERENCES `roles` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_channel_members_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='频道成员';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `channel_recommendations`
--

DROP TABLE IF EXISTS `channel_recommendations`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `channel_recommendations` (
  `id` char(26) NOT NULL,
  `user_id` char(26) NOT NULL,
  `channel_id` char(26) NOT NULL,
  `score` decimal(12,4) NOT NULL DEFAULT '0.0000',
  `reason` varchar(200) DEFAULT NULL COMMENT '推荐理由',
  `generated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_channel_recommendations_user_channel` (`user_id`,`channel_id`),
  KEY `idx_channel_recommendations_user_score` (`user_id`,`score`),
  KEY `fk_channel_recommendations_channel` (`channel_id`),
  CONSTRAINT `fk_channel_recommendations_channel` FOREIGN KEY (`channel_id`) REFERENCES `channels` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_channel_recommendations_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='频道推荐';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `channel_view_logs`
--

DROP TABLE IF EXISTS `channel_view_logs`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `channel_view_logs` (
  `id` char(26) NOT NULL,
  `channel_id` char(26) NOT NULL,
  `user_id` char(26) NOT NULL COMMENT '仅记录登录用户；游客浏览不参与 UV/PV 统计',
  `post_id` char(26) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_channel_view_logs_channel_created` (`channel_id`,`created_at`),
  KEY `idx_channel_view_logs_channel_user` (`channel_id`,`user_id`),
  KEY `fk_channel_view_logs_user` (`user_id`),
  KEY `fk_channel_view_logs_post` (`post_id`),
  CONSTRAINT `fk_channel_view_logs_channel` FOREIGN KEY (`channel_id`) REFERENCES `channels` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_channel_view_logs_post` FOREIGN KEY (`post_id`) REFERENCES `posts` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `fk_channel_view_logs_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='频道访问日志';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `channels`
--

DROP TABLE IF EXISTS `channels`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `channels` (
  `id` char(26) NOT NULL,
  `channel_no` bigint NOT NULL COMMENT '对外频道号',
  `name` varchar(64) NOT NULL,
  `avatar_url` varchar(500) NOT NULL,
  `cover_url` varchar(500) DEFAULT NULL,
  `description` varchar(1000) DEFAULT NULL,
  `type` varchar(16) NOT NULL COMMENT 'PUBLIC/PRIVATE',
  `visibility` varchar(16) NOT NULL COMMENT 'PUBLIC_VISIBLE/MEMBERS_ONLY',
  `owner_id` char(26) NOT NULL COMMENT '频道主',
  `member_count` bigint NOT NULL DEFAULT '0',
  `post_count` bigint NOT NULL DEFAULT '0',
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISSOLVED/BANNED',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `deleted_at` datetime(3) DEFAULT NULL,
  `mute_all_until` datetime(3) DEFAULT NULL COMMENT '全员禁言到期时间，NULL 表示未开启',
  `settings` json DEFAULT NULL COMMENT '频道扩展设置（预留：默认板块等）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_channels_no` (`channel_no`),
  KEY `idx_channels_owner` (`owner_id`),
  KEY `idx_channels_type_status` (`type`,`status`),
  CONSTRAINT `fk_channels_owner` FOREIGN KEY (`owner_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='频道';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `comment_likes`
--

DROP TABLE IF EXISTS `comment_likes`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `comment_likes` (
  `id` char(26) NOT NULL,
  `comment_id` char(26) NOT NULL,
  `user_id` char(26) NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_comment_likes_comment_user` (`comment_id`,`user_id`),
  KEY `idx_comment_likes_user` (`user_id`),
  CONSTRAINT `fk_comment_likes_comment` FOREIGN KEY (`comment_id`) REFERENCES `comments` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_comment_likes_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='评论点赞';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `comments`
--

DROP TABLE IF EXISTS `comments`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `comments` (
  `id` char(26) NOT NULL,
  `post_id` char(26) NOT NULL,
  `author_id` char(26) NOT NULL,
  `parent_id` char(26) DEFAULT NULL COMMENT '顶级评论为空；回复指向顶级评论',
  `reply_to_user_id` char(26) DEFAULT NULL,
  `content` text NOT NULL,
  `media` json DEFAULT NULL COMMENT '[{type,url,thumbUrl}]',
  `emoji` json DEFAULT NULL,
  `like_count` bigint NOT NULL DEFAULT '0',
  `status` varchar(16) NOT NULL DEFAULT 'PUBLISHED' COMMENT 'PUBLISHED/DELETED/HIDDEN',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `deleted_at` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_comments_post_created` (`post_id`,`status`,`created_at`),
  KEY `idx_comments_post_likes` (`post_id`,`status`,`like_count`),
  KEY `idx_comments_parent` (`parent_id`),
  KEY `fk_comments_author` (`author_id`),
  KEY `fk_comments_reply_to` (`reply_to_user_id`),
  CONSTRAINT `fk_comments_author` FOREIGN KEY (`author_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_comments_parent` FOREIGN KEY (`parent_id`) REFERENCES `comments` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_comments_post` FOREIGN KEY (`post_id`) REFERENCES `posts` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_comments_reply_to` FOREIGN KEY (`reply_to_user_id`) REFERENCES `users` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='评论';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `daily_channel_stats`
--

DROP TABLE IF EXISTS `daily_channel_stats`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `daily_channel_stats` (
  `id` char(26) NOT NULL COMMENT 'ULID',
  `channel_id` char(26) NOT NULL,
  `stat_date` date NOT NULL COMMENT '统计自然日（Asia/Shanghai）',
  `member_count` bigint NOT NULL DEFAULT '0' COMMENT '当日结束成员数',
  `new_members` bigint NOT NULL DEFAULT '0',
  `lost_members` bigint NOT NULL DEFAULT '0',
  `visit_uv` bigint NOT NULL DEFAULT '0' COMMENT '访问人数（去重）',
  `post_views` bigint NOT NULL DEFAULT '0' COMMENT '浏览量（PV）',
  `active_users` bigint NOT NULL DEFAULT '0' COMMENT '活跃用户（发帖/评论/点赞/浏览/分享去重）',
  `active_rate` decimal(6,4) NOT NULL DEFAULT '0.0000' COMMENT '活跃率 = 活跃用户 / 成员数',
  `posts` bigint NOT NULL DEFAULT '0',
  `comments` bigint NOT NULL DEFAULT '0',
  `likes` bigint NOT NULL DEFAULT '0',
  `shares` bigint NOT NULL DEFAULT '0',
  `interactions` bigint NOT NULL DEFAULT '0' COMMENT '点赞 + 评论 + 分享',
  `interaction_rate` decimal(6,4) NOT NULL DEFAULT '0.0000' COMMENT '互动率 = 互动 / 浏览量',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_daily_channel_stats_channel_date` (`channel_id`,`stat_date`),
  KEY `idx_daily_channel_stats_date` (`stat_date`),
  CONSTRAINT `fk_daily_channel_stats_channel` FOREIGN KEY (`channel_id`) REFERENCES `channels` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='频道日聚合统计';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `dm_conversations`
--

DROP TABLE IF EXISTS `dm_conversations`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `dm_conversations` (
  `id` char(26) NOT NULL COMMENT 'ULID',
  `user_low_id` char(26) NOT NULL COMMENT '参与者（ID 字典序较小者）',
  `user_high_id` char(26) NOT NULL COMMENT '参与者（ID 字典序较大者）',
  `last_message_id` char(26) DEFAULT NULL COMMENT '最后一条消息（冗余指针，不建外键避免循环依赖）',
  `last_message_sender_id` char(26) DEFAULT NULL,
  `last_message_type` varchar(16) DEFAULT NULL COMMENT 'TEXT/IMAGE/POST',
  `last_message_preview` varchar(200) DEFAULT NULL COMMENT '会话列表预览文本',
  `last_message_at` datetime(3) NOT NULL COMMENT '会话活跃时间（创建时=创建时间），会话列表排序键',
  `unread_low` bigint NOT NULL DEFAULT '0' COMMENT 'user_low_id 侧未读数',
  `unread_high` bigint NOT NULL DEFAULT '0' COMMENT 'user_high_id 侧未读数',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dm_conversations_pair` (`user_low_id`,`user_high_id`),
  KEY `idx_dm_conversations_low_activity` (`user_low_id`,`last_message_at`,`id`),
  KEY `idx_dm_conversations_high_activity` (`user_high_id`,`last_message_at`,`id`),
  CONSTRAINT `fk_dm_conversations_high` FOREIGN KEY (`user_high_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_dm_conversations_low` FOREIGN KEY (`user_low_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='私信会话';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `dm_messages`
--

DROP TABLE IF EXISTS `dm_messages`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `dm_messages` (
  `id` char(26) NOT NULL COMMENT 'ULID（字典序即发送时间序，作历史游标）',
  `conversation_id` char(26) NOT NULL,
  `sender_id` char(26) NOT NULL,
  `receiver_id` char(26) NOT NULL,
  `type` varchar(16) NOT NULL COMMENT 'TEXT/IMAGE/POST',
  `content` text COMMENT 'TEXT 正文 / 图片说明 / 分享附带文本',
  `payload` json DEFAULT NULL COMMENT 'IMAGE: {url,thumbUrl}；POST: {postId,title,channelId,channelName,coverUrl}',
  `client_message_id` varchar(64) DEFAULT NULL COMMENT '客户端消息 ID，用于发送重试去重',
  `is_read` tinyint(1) NOT NULL DEFAULT '0',
  `read_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dm_messages_client` (`conversation_id`,`sender_id`,`client_message_id`),
  KEY `idx_dm_messages_conversation_id` (`conversation_id`,`id`),
  KEY `idx_dm_messages_conversation_created` (`conversation_id`,`created_at`),
  KEY `idx_dm_messages_unread` (`conversation_id`,`receiver_id`,`is_read`),
  KEY `idx_dm_messages_sender` (`sender_id`),
  KEY `fk_dm_messages_receiver` (`receiver_id`),
  CONSTRAINT `fk_dm_messages_conversation` FOREIGN KEY (`conversation_id`) REFERENCES `dm_conversations` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_dm_messages_receiver` FOREIGN KEY (`receiver_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_dm_messages_sender` FOREIGN KEY (`sender_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='私信消息';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `friend_relations`
--

DROP TABLE IF EXISTS `friend_relations`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `friend_relations` (
  `id` char(26) NOT NULL COMMENT 'ULID',
  `user_id` char(26) NOT NULL COMMENT '主体用户（申请方 / 拉黑方）',
  `target_user_id` char(26) NOT NULL COMMENT '对象用户（被申请方 / 被拉黑方）',
  `status` varchar(16) NOT NULL COMMENT 'PENDING/ACCEPTED/REJECTED/BLOCKED',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_friend_relations_user_target` (`user_id`,`target_user_id`),
  KEY `idx_friend_relations_user_status` (`user_id`,`status`,`id`),
  KEY `idx_friend_relations_target_status` (`target_user_id`,`status`,`id`),
  CONSTRAINT `fk_friend_relations_target` FOREIGN KEY (`target_user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_friend_relations_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='好友关系（申请/好友/拉黑）';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `hot_keywords`
--

DROP TABLE IF EXISTS `hot_keywords`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `hot_keywords` (
  `id` char(26) NOT NULL,
  `keyword` varchar(100) NOT NULL,
  `search_count` bigint NOT NULL DEFAULT '0' COMMENT '统计窗口内搜索次数',
  `last_searched_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_hot_keywords_keyword` (`keyword`),
  KEY `idx_hot_keywords_count` (`search_count`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='搜索热词聚合';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `join_requests`
--

DROP TABLE IF EXISTS `join_requests`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `join_requests` (
  `id` char(26) NOT NULL,
  `channel_id` char(26) NOT NULL,
  `user_id` char(26) NOT NULL,
  `invite_id` char(26) DEFAULT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED',
  `reviewed_by` char(26) DEFAULT NULL,
  `reviewed_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `review_note` varchar(200) DEFAULT NULL COMMENT '审批备注（如拒绝原因）',
  PRIMARY KEY (`id`),
  KEY `idx_join_requests_channel_status` (`channel_id`,`status`),
  KEY `idx_join_requests_user` (`user_id`),
  KEY `fk_join_requests_invite` (`invite_id`),
  KEY `fk_join_requests_reviewer` (`reviewed_by`),
  CONSTRAINT `fk_join_requests_channel` FOREIGN KEY (`channel_id`) REFERENCES `channels` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_join_requests_invite` FOREIGN KEY (`invite_id`) REFERENCES `channel_invites` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `fk_join_requests_reviewer` FOREIGN KEY (`reviewed_by`) REFERENCES `users` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `fk_join_requests_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='加入申请';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `media_files`
--

DROP TABLE IF EXISTS `media_files`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `media_files` (
  `id` char(26) NOT NULL,
  `uploader_id` char(26) NOT NULL,
  `media_type` varchar(16) NOT NULL COMMENT 'IMAGE/VIDEO',
  `storage_path` varchar(500) NOT NULL,
  `url` varchar(500) NOT NULL,
  `thumb_url` varchar(500) DEFAULT NULL,
  `size_bytes` bigint NOT NULL DEFAULT '0',
  `width` int DEFAULT NULL,
  `height` int DEFAULT NULL,
  `duration_ms` bigint DEFAULT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DELETED',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_media_files_uploader` (`uploader_id`),
  CONSTRAINT `fk_media_files_uploader` FOREIGN KEY (`uploader_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='上传媒体文件';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `notifications`
--

DROP TABLE IF EXISTS `notifications`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `notifications` (
  `id` char(26) NOT NULL,
  `user_id` char(26) NOT NULL,
  `type` varchar(32) NOT NULL COMMENT 'POST_LIKED/POST_COMMENTED/COMMENT_REPLIED/SYSTEM',
  `channel_id` char(26) DEFAULT NULL,
  `post_id` char(26) DEFAULT NULL,
  `comment_id` char(26) DEFAULT NULL,
  `actor_id` char(26) DEFAULT NULL,
  `title` varchar(200) DEFAULT NULL,
  `content` varchar(500) DEFAULT NULL,
  `is_read` tinyint(1) NOT NULL DEFAULT '0',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `category` varchar(16) NOT NULL DEFAULT 'INTERACTION' COMMENT 'INTERACTION/SYSTEM',
  `payload` json DEFAULT NULL COMMENT '跳转与展示附加数据（postId/commentId 等冗余便于前端）',
  `read_at` datetime(3) DEFAULT NULL COMMENT '已读时间',
  PRIMARY KEY (`id`),
  KEY `idx_notifications_user_read_created` (`user_id`,`is_read`,`created_at`),
  KEY `idx_notifications_user_id` (`user_id`,`id`),
  KEY `fk_notifications_channel` (`channel_id`),
  KEY `fk_notifications_post` (`post_id`),
  KEY `fk_notifications_comment` (`comment_id`),
  KEY `fk_notifications_actor` (`actor_id`),
  KEY `idx_notifications_user_category_created` (`user_id`,`category`,`created_at`),
  CONSTRAINT `fk_notifications_actor` FOREIGN KEY (`actor_id`) REFERENCES `users` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `fk_notifications_channel` FOREIGN KEY (`channel_id`) REFERENCES `channels` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `fk_notifications_comment` FOREIGN KEY (`comment_id`) REFERENCES `comments` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `fk_notifications_post` FOREIGN KEY (`post_id`) REFERENCES `posts` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `fk_notifications_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通知';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `platform_admin_grants`
--

DROP TABLE IF EXISTS `platform_admin_grants`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `platform_admin_grants` (
  `id` char(26) NOT NULL COMMENT 'ULID',
  `user_id` char(26) NOT NULL,
  `role` varchar(24) NOT NULL COMMENT 'SUPER_ADMIN/OPERATOR/MODERATOR/VIEWER',
  `granted_by` char(26) DEFAULT NULL,
  `granted_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `expires_at` datetime(3) DEFAULT NULL COMMENT 'null=长期有效',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_platform_admin_grants_user` (`user_id`),
  KEY `idx_platform_admin_grants_role` (`role`),
  CONSTRAINT `fk_platform_admin_grants_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台管理员授权';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `platform_audit_logs`
--

DROP TABLE IF EXISTS `platform_audit_logs`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `platform_audit_logs` (
  `id` char(26) NOT NULL COMMENT 'ULID',
  `actor_id` char(26) DEFAULT NULL,
  `actor_name` varchar(32) DEFAULT NULL COMMENT '操作人快照',
  `action` varchar(64) NOT NULL COMMENT 'platform.config.update / platform.user.ban',
  `target_type` varchar(32) DEFAULT NULL COMMENT 'USER/CHANNEL/POST/COMMENT/REPORT/CONFIG/ADMIN',
  `target_id` char(26) DEFAULT NULL,
  `detail` json DEFAULT NULL COMMENT 'before/after、原因等',
  `ip` varchar(45) DEFAULT NULL,
  `user_agent` varchar(255) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_platform_audit_logs_created` (`created_at`),
  KEY `idx_platform_audit_logs_actor_created` (`actor_id`,`created_at`),
  KEY `idx_platform_audit_logs_action_created` (`action`,`created_at`),
  KEY `idx_platform_audit_logs_target` (`target_type`,`target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台操作审计';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `platform_setting_histories`
--

DROP TABLE IF EXISTS `platform_setting_histories`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `platform_setting_histories` (
  `id` char(26) NOT NULL COMMENT 'ULID',
  `setting_key` varchar(100) NOT NULL,
  `old_value` text COMMENT '变更前（null=此前未覆盖）',
  `new_value` text COMMENT '变更后（null=恢复默认）',
  `actor_id` char(26) DEFAULT NULL,
  `ip` varchar(45) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_platform_setting_histories_key_created` (`setting_key`,`created_at`),
  KEY `idx_platform_setting_histories_actor` (`actor_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台配置变更历史';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `platform_settings`
--

DROP TABLE IF EXISTS `platform_settings`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `platform_settings` (
  `setting_key` varchar(100) NOT NULL COMMENT '配置键，如 site.name',
  `setting_value` text COMMENT '覆盖值（字符串形式，按 value_type 解析）',
  `updated_by` char(26) DEFAULT NULL COMMENT '最后修改人',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`setting_key`),
  KEY `fk_platform_settings_updated_by` (`updated_by`),
  CONSTRAINT `fk_platform_settings_updated_by` FOREIGN KEY (`updated_by`) REFERENCES `users` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台运行时配置';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `post_likes`
--

DROP TABLE IF EXISTS `post_likes`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `post_likes` (
  `id` char(26) NOT NULL,
  `post_id` char(26) NOT NULL,
  `user_id` char(26) NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_post_likes_post_user` (`post_id`,`user_id`),
  KEY `idx_post_likes_user` (`user_id`),
  CONSTRAINT `fk_post_likes_post` FOREIGN KEY (`post_id`) REFERENCES `posts` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_post_likes_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='帖子点赞';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `post_media`
--

DROP TABLE IF EXISTS `post_media`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `post_media` (
  `id` char(26) NOT NULL,
  `post_id` char(26) NOT NULL,
  `media_type` varchar(16) NOT NULL COMMENT 'IMAGE/VIDEO',
  `url` varchar(500) NOT NULL,
  `thumb_url` varchar(500) DEFAULT NULL,
  `width` int DEFAULT NULL,
  `height` int DEFAULT NULL,
  `duration_ms` bigint DEFAULT NULL,
  `sort` int NOT NULL DEFAULT '0',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_post_media_post` (`post_id`,`sort`),
  CONSTRAINT `fk_post_media_post` FOREIGN KEY (`post_id`) REFERENCES `posts` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='帖子媒体';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `posts`
--

DROP TABLE IF EXISTS `posts`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `posts` (
  `id` char(26) NOT NULL COMMENT 'ULID，字典序即发布时间序',
  `channel_id` char(26) NOT NULL,
  `board_id` char(26) NOT NULL,
  `author_id` char(26) NOT NULL,
  `title` varchar(200) DEFAULT NULL COMMENT '长文标题',
  `content` mediumtext NOT NULL COMMENT '正文（PLAIN 或净化后的 HTML）',
  `content_format` varchar(16) NOT NULL DEFAULT 'PLAIN' COMMENT 'PLAIN/HTML',
  `cover_url` varchar(500) DEFAULT NULL,
  `view_count` bigint NOT NULL DEFAULT '0',
  `like_count` bigint NOT NULL DEFAULT '0',
  `comment_count` bigint NOT NULL DEFAULT '0',
  `share_count` bigint NOT NULL DEFAULT '0',
  `is_pinned` tinyint(1) NOT NULL DEFAULT '0',
  `is_essence` tinyint(1) NOT NULL DEFAULT '0',
  `pinned_at` datetime(3) DEFAULT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'PUBLISHED' COMMENT 'PUBLISHED/DELETED/HIDDEN',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `deleted_at` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_posts_channel_created` (`channel_id`,`status`,`created_at`),
  KEY `idx_posts_channel_board_created` (`channel_id`,`board_id`,`status`,`created_at`),
  KEY `idx_posts_author` (`author_id`),
  KEY `idx_posts_pinned` (`channel_id`,`is_pinned`,`pinned_at`),
  KEY `fk_posts_board` (`board_id`),
  CONSTRAINT `fk_posts_author` FOREIGN KEY (`author_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_posts_board` FOREIGN KEY (`board_id`) REFERENCES `boards` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_posts_channel` FOREIGN KEY (`channel_id`) REFERENCES `channels` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='帖子';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `refresh_tokens`
--

DROP TABLE IF EXISTS `refresh_tokens`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `refresh_tokens` (
  `id` char(26) NOT NULL,
  `user_id` char(26) NOT NULL,
  `token_hash` char(64) NOT NULL COMMENT 'SHA-256 十六进制',
  `expires_at` datetime(3) NOT NULL,
  `revoked_at` datetime(3) DEFAULT NULL,
  `user_agent` varchar(255) DEFAULT NULL,
  `ip` varchar(45) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refresh_tokens_hash` (`token_hash`),
  KEY `idx_refresh_tokens_user` (`user_id`),
  CONSTRAINT `fk_refresh_tokens_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='刷新令牌';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `reports`
--

DROP TABLE IF EXISTS `reports`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `reports` (
  `id` char(26) NOT NULL COMMENT 'ULID',
  `reporter_id` char(26) NOT NULL,
  `channel_id` char(26) DEFAULT NULL COMMENT '所属频道；平台级举报为空',
  `target_type` varchar(16) NOT NULL COMMENT 'POST/COMMENT/USER/CHANNEL',
  `target_id` char(26) NOT NULL,
  `reason` varchar(32) NOT NULL COMMENT 'SPAM/ABUSE/PORN/ILLEGAL/OTHER',
  `description` varchar(500) DEFAULT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/ACCEPTED/REJECTED',
  `handler_id` char(26) DEFAULT NULL,
  `handle_action` varchar(32) DEFAULT NULL COMMENT 'NONE/DELETE_CONTENT/MUTE_AUTHOR/WARN',
  `handle_note` varchar(500) DEFAULT NULL,
  `handled_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_reports_status_created` (`status`,`created_at`),
  KEY `idx_reports_channel_status` (`channel_id`,`status`),
  KEY `idx_reports_target` (`target_type`,`target_id`),
  KEY `idx_reports_reporter` (`reporter_id`),
  KEY `fk_reports_handler` (`handler_id`),
  CONSTRAINT `fk_reports_channel` FOREIGN KEY (`channel_id`) REFERENCES `channels` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_reports_handler` FOREIGN KEY (`handler_id`) REFERENCES `users` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `fk_reports_reporter` FOREIGN KEY (`reporter_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='内容举报';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `roles`
--

DROP TABLE IF EXISTS `roles`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `roles` (
  `id` char(26) NOT NULL,
  `channel_id` char(26) NOT NULL,
  `name` varchar(32) NOT NULL,
  `description` varchar(200) DEFAULT NULL,
  `permissions` json NOT NULL COMMENT '权限点数组，如 ["post.create"]',
  `scope_board_id` char(26) DEFAULT NULL COMMENT '子板块管理员范围',
  `is_system` tinyint(1) NOT NULL DEFAULT '0',
  `sort` int NOT NULL DEFAULT '0',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_roles_channel_name` (`channel_id`,`name`),
  KEY `idx_roles_channel` (`channel_id`),
  KEY `fk_roles_board` (`scope_board_id`),
  CONSTRAINT `fk_roles_board` FOREIGN KEY (`scope_board_id`) REFERENCES `boards` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `fk_roles_channel` FOREIGN KEY (`channel_id`) REFERENCES `channels` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='频道身份组';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `search_history`
--

DROP TABLE IF EXISTS `search_history`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `search_history` (
  `id` char(26) NOT NULL,
  `user_id` char(26) NOT NULL,
  `keyword` varchar(100) NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_search_history_user_keyword` (`user_id`,`keyword`),
  KEY `idx_search_history_user_created` (`user_id`,`created_at`),
  CONSTRAINT `fk_search_history_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='搜索历史';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `search_logs`
--

DROP TABLE IF EXISTS `search_logs`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `search_logs` (
  `id` char(26) NOT NULL,
  `user_id` char(26) DEFAULT NULL,
  `keyword` varchar(100) NOT NULL,
  `scope` varchar(16) NOT NULL DEFAULT 'GLOBAL' COMMENT 'GLOBAL/CHANNEL',
  `channel_id` char(26) DEFAULT NULL,
  `result_count` int NOT NULL DEFAULT '0',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_search_logs_keyword` (`keyword`(32)),
  KEY `idx_search_logs_created` (`created_at`),
  KEY `fk_search_logs_user` (`user_id`),
  CONSTRAINT `fk_search_logs_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='搜索日志';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `sensitive_word_hits`
--

DROP TABLE IF EXISTS `sensitive_word_hits`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sensitive_word_hits` (
  `id` char(26) NOT NULL COMMENT 'ULID',
  `word_id` char(26) DEFAULT NULL,
  `word` varchar(100) NOT NULL COMMENT '命中词快照',
  `scene` varchar(16) NOT NULL COMMENT 'POST/COMMENT',
  `target_id` char(26) DEFAULT NULL,
  `user_id` char(26) DEFAULT NULL,
  `action` varchar(16) NOT NULL COMMENT '命中时的处置：BLOCK/REVIEW/MASK',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_sensitive_word_hits_created` (`created_at`),
  KEY `idx_sensitive_word_hits_word` (`word_id`,`created_at`),
  KEY `idx_sensitive_word_hits_target` (`scene`,`target_id`),
  KEY `fk_sensitive_word_hits_user` (`user_id`),
  CONSTRAINT `fk_sensitive_word_hits_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `fk_sensitive_word_hits_word` FOREIGN KEY (`word_id`) REFERENCES `sensitive_words` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='敏感词命中记录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `sensitive_words`
--

DROP TABLE IF EXISTS `sensitive_words`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sensitive_words` (
  `id` char(26) NOT NULL COMMENT 'ULID',
  `word` varchar(100) NOT NULL,
  `category` varchar(32) DEFAULT NULL COMMENT 'POLITICS/PORN/AD/ABUSE/CUSTOM',
  `action` varchar(16) NOT NULL DEFAULT 'REVIEW' COMMENT 'BLOCK/REVIEW/MASK',
  `enabled` tinyint(1) NOT NULL DEFAULT '1',
  `created_by` char(26) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sensitive_words_word` (`word`),
  KEY `idx_sensitive_words_enabled` (`enabled`),
  KEY `fk_sensitive_words_created_by` (`created_by`),
  CONSTRAINT `fk_sensitive_words_created_by` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='敏感词库';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `user_penalties`
--

DROP TABLE IF EXISTS `user_penalties`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_penalties` (
  `id` char(26) NOT NULL COMMENT 'ULID',
  `user_id` char(26) NOT NULL,
  `type` varchar(16) NOT NULL COMMENT 'BAN/MUTE/WARN',
  `scope` varchar(16) NOT NULL DEFAULT 'GLOBAL' COMMENT 'GLOBAL/CHANNEL',
  `channel_id` char(26) DEFAULT NULL COMMENT 'scope=CHANNEL 时的频道',
  `reason` varchar(200) NOT NULL,
  `report_id` char(26) DEFAULT NULL COMMENT '关联举报',
  `operator_id` char(26) DEFAULT NULL,
  `start_at` datetime(3) NOT NULL,
  `end_at` datetime(3) DEFAULT NULL COMMENT 'null=永久',
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/EXPIRED/REVOKED',
  `revoked_by` char(26) DEFAULT NULL,
  `revoked_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_user_penalties_user_status` (`user_id`,`status`,`end_at`),
  KEY `idx_user_penalties_created` (`created_at`),
  CONSTRAINT `fk_user_penalties_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户处罚记录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `users`
--

DROP TABLE IF EXISTS `users`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `users` (
  `id` char(26) NOT NULL COMMENT 'ULID',
  `username` varchar(32) NOT NULL COMMENT '登录名',
  `password_hash` varchar(100) NOT NULL COMMENT 'BCrypt 哈希',
  `nickname` varchar(32) NOT NULL COMMENT '展示名',
  `avatar_url` varchar(500) DEFAULT NULL COMMENT '头像',
  `signature` varchar(200) DEFAULT NULL COMMENT '签名',
  `gender` varchar(16) DEFAULT NULL COMMENT 'MALE/FEMALE/UNKNOWN',
  `region` varchar(64) DEFAULT NULL COMMENT '地区',
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/BANNED/DISABLED',
  `platform_role` varchar(16) NOT NULL DEFAULT 'USER' COMMENT '平台角色：USER/ADMIN',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `deleted_at` datetime(3) DEFAULT NULL COMMENT '软删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_users_username` (`username`),
  KEY `idx_users_status` (`status`),
  KEY `idx_users_platform_role` (`platform_role`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户账号';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-11-16  3:49:45
