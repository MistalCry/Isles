package com.isles.ai.provider;

/**
 * 非流式模型调用结果。
 */
public record AiChatResult(String content, String reasoningContent, AiUsage usage, String model) {
}
