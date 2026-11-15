package com.isles.ai.provider;

import java.util.List;

/**
 * 流式回答监听器：增量文本、推理内容、工具调用、完成与取消。
 *
 * <p>{@link #isCancelled()} 由调用方（SSE 停止按钮）置位，Provider 在读取下一个 chunk 前检查并停止读取。
 * 工具调用在流结束后一次性回调（{@link #onToolCalls}）：OpenAI 的 tool_calls 是分片下发的，
 * 必须先按 index 拼完 id/name/arguments 才能执行。
 */
public interface AiStreamListener {

    default void onDelta(String content) {
    }

    default void onReasoning(String content) {
    }

    default void onToolCalls(List<AiToolCall> toolCalls, String finishReason) {
    }

    default void onCompleted(AiUsage usage) {
    }

    default boolean isCancelled() {
        return false;
    }
}
