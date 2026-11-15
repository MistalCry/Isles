package com.isles.ai.provider;

import java.util.List;

/**
 * 模型调用抽象（docs/03 §5.2 AiProvider）：OpenAI 兼容协议，可替换为其它 Provider。
 */
public interface AiProvider {

    /**
     * 非流式对话。
     */
    AiChatResult chat(AiChatRequest request);

    /**
     * 流式对话：阻塞读取 SSE，逐 chunk 回调；{@link AiStreamListener#isCancelled()} 为 true 时提前返回。
     */
    void streamChat(AiChatRequest request, AiStreamListener listener);

    /**
     * 拉取上游可用模型列表（GET /models）。
     */
    List<String> listModels();

    /**
     * 连通性测试：能成功拉取模型列表即视为连通；失败抛 {@link AiProviderException}。
     */
    void testConnection();
}
