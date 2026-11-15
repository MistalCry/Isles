package com.isles.ai.provider;

/**
 * 模型请求的一次工具调用。
 *
 * <p>{@code arguments} 保持上游返回的原始 JSON 字符串（流式下是分片拼接结果），
 * 由工具执行方自行解析，避免解析失败时丢失模型意图。
 */
public record AiToolCall(String id, String name, String arguments) {

    public AiToolCall {
        arguments = arguments == null ? "{}" : arguments;
    }
}
