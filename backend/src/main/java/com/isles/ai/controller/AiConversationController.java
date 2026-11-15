package com.isles.ai.controller;

import com.isles.ai.dto.AiRequests;
import com.isles.ai.dto.AiResponses;
import com.isles.ai.service.AiConversationService;
import com.isles.common.pagination.PageResult;
import com.isles.common.security.CurrentUserProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 频道问问 / 频道助手会话接口（docs/03 FR-AIQA-01/06）。
 *
 * <p>全部接口需要登录，服务层逐会话校验归属（防 IDOR）；频道助手会话额外校验频道可见性。
 */
@RestController
@RequestMapping("/api/v1/ai/conversations")
@RequiredArgsConstructor
public class AiConversationController {

    private final AiConversationService conversationService;
    private final CurrentUserProvider currentUserProvider;

    /**
     * 会话列表：scope=GLOBAL 频道问问；scope=CHANNEL + channelId 频道助手。
     */
    @GetMapping
    public PageResult<AiResponses.ConversationResponse> list(@RequestParam(required = false) String scope,
                                                             @RequestParam(required = false) String channelId,
                                                             @RequestParam(required = false) String cursor,
                                                             @RequestParam(required = false) Integer limit) {
        return conversationService.listConversations(currentUserProvider.requireUserId(), scope, channelId,
                cursor, limit);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AiResponses.ConversationResponse create(@Valid @RequestBody AiRequests.CreateConversationRequest request) {
        return conversationService.createConversation(currentUserProvider.requireUserId(), request);
    }

    @GetMapping("/{id}")
    public AiResponses.ConversationResponse detail(@PathVariable String id) {
        return conversationService.getConversation(id, currentUserProvider.requireUserId());
    }

    @PatchMapping("/{id}")
    public AiResponses.ConversationResponse rename(@PathVariable String id,
                                                   @Valid @RequestBody AiRequests.RenameConversationRequest request) {
        return conversationService.renameConversation(id, currentUserProvider.requireUserId(), request.title());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        conversationService.deleteConversation(id, currentUserProvider.requireUserId());
    }

    /**
     * 会话消息（最新一页，按时间升序返回；{@code cursor} 用于加载更早消息）。
     */
    @GetMapping("/{id}/messages")
    public PageResult<AiResponses.MessageResponse> messages(@PathVariable String id,
                                                            @RequestParam(required = false) String cursor,
                                                            @RequestParam(required = false) Integer limit) {
        return conversationService.listMessages(id, currentUserProvider.requireUserId(), cursor, limit);
    }
}
