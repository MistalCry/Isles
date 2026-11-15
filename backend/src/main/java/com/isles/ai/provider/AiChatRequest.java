package com.isles.ai.provider;

import java.util.List;

/**
 * 一次模型调用请求（流式/非流式共用）。
 *
 * @param model            模型 ID（来自 ai_model_configs.model）
 * @param messages         完整消息序列（含系统提示词与历史）
 * @param stream           是否流式
 * @param deepThinking     是否启用深度思考/推理
 * @param maxTokens        最大输出 token（&lt;=0 时不下发）
 * @param timeoutSeconds   覆盖默认超时（null 用配置值）
 * @param reasoningCapable 模型是否具备推理能力（来自配置 {@code reasoningEnabled}）
 * @param thinkingMode     思考参数下发方式（REASONING_EFFORT / CHAT_TEMPLATE / BOTH / NONE）。
 *                         不同后端开关思考的方式不同：OpenAI 系用 {@code reasoning_effort}，
 *                         LM Studio/vLLM + Qwen3 用 {@code chat_template_kwargs.enable_thinking}；
 *                         关闭时必须显式下发，否则模型沿用默认继续思考。
 */
public record AiChatRequest(
        String model,
        List<AiChatMessage> messages,
        boolean stream,
        boolean deepThinking,
        int maxTokens,
        Integer timeoutSeconds,
        boolean reasoningCapable,
        String thinkingMode) {

    public AiChatRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    public static AiChatRequest of(String model, List<AiChatMessage> messages, boolean stream,
                                   boolean deepThinking, int maxTokens) {
        return new AiChatRequest(model, messages, stream, deepThinking, maxTokens, null, false, "REASONING_EFFORT");
    }

    public static AiChatRequest of(String model, List<AiChatMessage> messages, boolean stream,
                                   boolean deepThinking, int maxTokens, boolean reasoningCapable) {
        return new AiChatRequest(model, messages, stream, deepThinking, maxTokens, null, reasoningCapable,
                "REASONING_EFFORT");
    }

    public static AiChatRequest of(String model, List<AiChatMessage> messages, boolean stream,
                                   boolean deepThinking, int maxTokens, boolean reasoningCapable,
                                   String thinkingMode) {
        return new AiChatRequest(model, messages, stream, deepThinking, maxTokens, null, reasoningCapable,
                thinkingMode == null ? "REASONING_EFFORT" : thinkingMode);
    }
}
