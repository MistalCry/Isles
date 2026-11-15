package com.isles.ai.provider;

/**
 * 流式回答监听器：增量文本、推理内容、完成与取消。
 *
 * <p>{@link #isCancelled()} 由调用方（SSE 停止按钮）置位，Provider 在读取下一个 chunk 前检查并停止读取。
 */
public interface AiStreamListener {

    default void onDelta(String content) {
    }

    default void onReasoning(String content) {
    }

    default void onCompleted(AiUsage usage) {
    }

    default boolean isCancelled() {
        return false;
    }
}
