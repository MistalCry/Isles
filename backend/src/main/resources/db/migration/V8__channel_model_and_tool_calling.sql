-- =============================================================================
-- V8__channel_model_and_tool_calling.sql
-- 1) 频道助手可选模型：channel_ai_settings.model_config_id（空 = 跟随平台默认）
-- 2) AI 工具调用开关：ai_model_configs.tool_call_enabled（默认关）
-- 依据：docs/11 §4.2（助手模型）、§5.3（工具调用）、docs/05 §2.13
-- 约定：DATETIME(3) 存 UTC、外键/索引显式命名（fk_/idx_）、迁移只追加不回改。
-- =============================================================================

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- 1. ai_model_configs.tool_call_enabled
--    默认 0：模型是否支持 function calling 因后端差异很大（LM Studio 小模型常不支持），
--    未开启时问答走关键词检索，功能不受影响。
-- -----------------------------------------------------------------------------
ALTER TABLE ai_model_configs
    ADD COLUMN tool_call_enabled TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '是否启用工具调用（function calling）；关闭时使用关键词检索'
        AFTER thinking_mode;

-- -----------------------------------------------------------------------------
-- 2. channel_ai_settings.model_config_id
--    频道主可为频道助手单独指定模型；为空表示跟随平台默认模型。
--    模型被删除时置空（ON DELETE SET NULL），运行期回落平台默认并记日志。
-- -----------------------------------------------------------------------------
ALTER TABLE channel_ai_settings
    ADD COLUMN model_config_id CHAR(26) NULL
        COMMENT '频道助手使用的模型配置；空=跟随平台默认'
        AFTER persona,
    ADD KEY idx_channel_ai_settings_model_config (model_config_id),
    ADD CONSTRAINT fk_channel_ai_settings_model_config
        FOREIGN KEY (model_config_id) REFERENCES ai_model_configs (id)
        ON DELETE SET NULL ON UPDATE RESTRICT;
