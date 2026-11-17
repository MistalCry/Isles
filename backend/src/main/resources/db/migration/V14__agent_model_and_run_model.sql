-- =============================================================================
-- V14__agent_model_and_run_model.sql
-- 1) 每个代理可选模型：agent_profiles.model_config_id（NULL = 跟随平台默认）
-- 2) 运行日志记「实际用的模型」：agent_runs.model_config_id + model 快照
--
-- 背景：
--   * P0-2：AgentRuntime 原先固定 resolveDefault()，所有代理共用一个模型，后台无法按人设分工；
--   * 运行日志原先只有 token 数，无法回答「这次到底用的哪个模型」，两阶段两次调用更是无从对账。
--     说明：第 18 轮汇报里提到的「agent_runs 已有 model 字段」有误——那是 ai_task_logs（V9），
--     agent_runs 建表时（V13）没有该列，故本轮补上。
--
-- 依据：docs/12 §3.2 / §3.4、docs/06 §17
-- 约定：ULID CHAR(26)、utf8mb4 / utf8mb4_0900_ai_ci、DATETIME(3) 存 UTC、
--       外键/索引显式命名（fk_/idx_）、迁移只追加不回改。
-- =============================================================================

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- 1. agent_profiles.model_config_id
--    NULL = 跟随平台默认（is_default=1 → 首个启用模型）。
--    模型被删除时置空（ON DELETE SET NULL）：人设不能因为删了一个模型就整行失败，
--    运行期会回落平台默认并在 agent_runs.error 记「指定模型不可用，已回落默认」。
-- -----------------------------------------------------------------------------
ALTER TABLE agent_profiles
    ADD COLUMN model_config_id CHAR(26) NULL
        COMMENT '代理使用的模型配置；NULL=跟随平台默认'
        AFTER tools_enabled,
    ADD KEY idx_agent_profiles_model_config (model_config_id),
    ADD CONSTRAINT fk_agent_profiles_model_config
        FOREIGN KEY (model_config_id) REFERENCES ai_model_configs (id)
        ON DELETE SET NULL ON UPDATE RESTRICT;

-- -----------------------------------------------------------------------------
-- 2. agent_runs：实际使用的模型（与 V9 的 ai_task_logs 同构）
--    model_config_id 配置删除后置空；model 是模型名快照（如 qwen2.5-7b），删配置后仍可追溯。
--    两阶段 tick 的研究/产出共用同一个模型，因此一次运行只有一对值。
-- -----------------------------------------------------------------------------
ALTER TABLE agent_runs
    ADD COLUMN model_config_id CHAR(26)     NULL
        COMMENT '本次使用的模型配置；配置删除后置空'
        AFTER tokens_out,
    ADD COLUMN model           VARCHAR(128) NULL
        COMMENT '模型名快照（来自 ai_model_configs.model）；两阶段共用同一模型'
        AFTER model_config_id,
    ADD KEY idx_agent_runs_model_config (model_config_id),
    ADD CONSTRAINT fk_agent_runs_model_config
        FOREIGN KEY (model_config_id) REFERENCES ai_model_configs (id)
        ON DELETE SET NULL ON UPDATE RESTRICT;
