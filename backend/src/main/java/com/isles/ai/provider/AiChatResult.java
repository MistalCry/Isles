package com.isles.ai.provider;

import java.util.List;

/**
 * 非流式模型调用结果。
 *
 * @param content         回答正文（工具调用轮次可能为空）
 * @param reasoningContent 推理内容（模型支持时）
 * @param usage           token 用量
 * @param model           上游返回的模型名
 * @param toolCalls       模型请求的工具调用；为空表示这是最终回答
 * @param finishReason    上游 finish_reason（stop / tool_calls / length …）
 */
public record AiChatResult(String content, String reasoningContent, AiUsage usage, String model,
                           List<AiToolCall> toolCalls, String finishReason) {

    public AiChatResult {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    /** 兼容旧调用：无工具调用。 */
    public AiChatResult(String content, String reasoningContent, AiUsage usage, String model) {
        this(content, reasoningContent, usage, model, List.of(), null);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
