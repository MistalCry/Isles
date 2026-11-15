package com.isles.ai.controller;

import com.isles.ai.dto.AiRequests;
import com.isles.ai.dto.AiResponses;
import com.isles.ai.service.AiChatService;
import com.isles.common.security.CurrentUserProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 对话流式接口（SSE，docs/03 FR-AIQA-09）。
 *
 * <p>SSE 事件：{@code meta}（回答消息 id/模型/来源引用）→ {@code delta}（正文增量）/
 * {@code reasoning}（深度思考增量）→ {@code done}；失败时追加 {@code error}。
 * 同步校验失败（未配置模型、会话不存在、配额/并发超限）直接返回统一 JSON 错误信封。
 */
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiChatController {

    private final AiChatService chatService;
    private final CurrentUserProvider currentUserProvider;

    /**
     * 发送提问（流式）。regenerate=true 时对最后一条提问重新生成。
     */
    @PostMapping(value = "/conversations/{id}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String id,
                             @Valid @RequestBody AiRequests.SendMessageRequest request) {
        return chatService.streamSend(id, request, currentUserProvider.requireUserId());
    }

    /**
     * 重新生成最后一条回答（流式）。
     */
    @PostMapping(value = "/conversations/{id}/regenerate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter regenerate(@PathVariable String id,
                                 @RequestBody AiRequests.RegenerateRequest request) {
        AiRequests.RegenerateRequest safeRequest = request == null
                ? new AiRequests.RegenerateRequest(null, false)
                : request;
        return chatService.streamRegenerate(id, safeRequest, currentUserProvider.requireUserId());
    }

    /**
     * 编辑历史提问并重发（流式）：截断该消息之后的全部消息。
     */
    @PostMapping(value = "/messages/{id}/edit-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter editAndResend(@PathVariable String id,
                                    @Valid @RequestBody AiRequests.EditMessageRequest request) {
        return chatService.streamEdit(id, request, currentUserProvider.requireUserId());
    }

    /**
     * 停止生成：置位取消标记后，服务端在下个 chunk 前结束并落库为 ABORTED。
     */
    @PostMapping("/messages/{id}/stop")
    public AiResponses.StopResponse stop(@PathVariable String id) {
        currentUserProvider.requireUserId();
        return chatService.stop(id);
    }
}
