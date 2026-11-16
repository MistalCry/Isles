-- =============================================================================
-- V9__ai_task_log_model.sql
-- 频道助手任务日志记录「本次用的哪个模型」
--
-- 背景：任务日志原先只有状态与结果，无法回答「这次日报/自动发帖用的是频道指定模型还是平台默认」，
--       测试只能靠 mock 侧请求日志间接推断。补两列后，日志页可直接展示，也便于排查换模型后的行为差异。
-- 约定：DATETIME(3) 存 UTC、外键/索引显式命名（fk_/idx_）、迁移只追加不回改。
-- =============================================================================

SET NAMES utf8mb4;

-- model_config_id：本次实际使用的模型配置；配置被删除时置空（历史日志仍保留 model 快照）
-- model：模型 ID 快照（如 qwen2.5-7b），便于配置删除后仍能看出当时用的模型
ALTER TABLE ai_task_logs
    ADD COLUMN model_config_id CHAR(26)     NULL COMMENT '本次使用的模型配置；配置删除后置空' AFTER error_message,
    ADD COLUMN model           VARCHAR(128) NULL COMMENT '模型 ID 快照（来自 ai_model_configs.model）' AFTER model_config_id,
    ADD KEY idx_ai_task_logs_model_config (model_config_id),
    ADD CONSTRAINT fk_ai_task_logs_model_config
        FOREIGN KEY (model_config_id) REFERENCES ai_model_configs (id)
        ON DELETE SET NULL ON UPDATE RESTRICT;
