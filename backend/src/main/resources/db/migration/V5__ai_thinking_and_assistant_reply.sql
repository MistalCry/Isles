-- =====================================================================
-- V5：AI 思考模式 + 频道助手被动回复
--
-- 1) ai_model_configs.thinking_mode：不同后端关闭/开启「深度思考」的方式不一致
--    （OpenAI 系用 reasoning_effort，LM Studio/vLLM + Qwen3 用 chat_template_kwargs），
--    按模型配置，避免「开关无效」。
-- 2) channel_ai_settings.auto_reply_enabled：有人回复助手评论时，助手自动接话。
-- =====================================================================

ALTER TABLE ai_model_configs
    ADD COLUMN thinking_mode VARCHAR(32) NOT NULL DEFAULT 'REASONING_EFFORT'
        COMMENT '思考参数下发方式：REASONING_EFFORT / CHAT_TEMPLATE / BOTH / NONE'
        AFTER reasoning_enabled;

-- 本地推理服务（LM Studio / vLLM / Ollama）两种参数都发，兼容性最好：
-- 实测 Qwen3 在 LM Studio 下只有 reasoning_effort=none 能真正关闭思考，
-- 而部分后端只认 chat_template_kwargs，BOTH 可同时覆盖。
UPDATE ai_model_configs
SET thinking_mode = 'BOTH'
WHERE base_url LIKE '%127.0.0.1%' OR base_url LIKE '%localhost%' OR base_url LIKE '%0.0.0.0%';

ALTER TABLE channel_ai_settings
    ADD COLUMN auto_reply_enabled TINYINT(1) NOT NULL DEFAULT 1
        COMMENT '有人回复助手评论时自动接话'
        AFTER auto_like_enabled;
