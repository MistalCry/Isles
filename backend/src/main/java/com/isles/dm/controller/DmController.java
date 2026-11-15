package com.isles.dm.controller;

import com.isles.common.pagination.PageResult;
import com.isles.common.security.CurrentUserProvider;
import com.isles.dm.dto.ConversationResponse;
import com.isles.dm.dto.CreateConversationRequest;
import com.isles.dm.dto.DmMessageResponse;
import com.isles.dm.dto.MarkReadResponse;
import com.isles.dm.dto.SendMessageRequest;
import com.isles.dm.dto.UnreadCountResponse;
import com.isles.dm.service.DmService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 私信接口（docs/03 §4.12；docs/05 §1.1 统一分页/错误信封；WP2 新增，需补充 docs/05 契约）。
 *
 * <p>全部需要登录；消息历史与会话列表使用游标分页（docs/05 §1）；
 * 实时推送走 STOMP {@code /user/queue/dm}，REST 接口同时是断线补拉兜底。
 */
@RestController
@RequestMapping("/api/v1/dm")
@RequiredArgsConstructor
public class DmController {

    private final DmService dmService;
    private final CurrentUserProvider currentUserProvider;

    /**
     * 打开（或创建）与指定用户的会话；任意用户可发起私信，无需好友。
     */
    @PostMapping("/conversations")
    public ConversationResponse openConversation(@Valid @RequestBody CreateConversationRequest request) {
        return dmService.openConversation(request, currentUserProvider.requireUserId());
    }

    /**
     * 会话列表：最近消息 + 未读数，按活跃时间倒序游标分页。
     */
    @GetMapping("/conversations")
    public PageResult<ConversationResponse> listConversations(@RequestParam(required = false) String cursor,
                                                              @RequestParam(required = false) Integer limit) {
        return dmService.listConversations(currentUserProvider.requireUserId(), cursor, limit);
    }

    /**
     * 会话详情（仅参与者）。
     */
    @GetMapping("/conversations/{id}")
    public ConversationResponse getConversation(@PathVariable String id) {
        return dmService.getConversation(id, currentUserProvider.requireUserId());
    }

    /**
     * 发送消息：TEXT / IMAGE / POST（分享帖子卡片）。
     */
    @PostMapping("/conversations/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public DmMessageResponse sendMessage(@PathVariable String id, @Valid @RequestBody SendMessageRequest request) {
        return dmService.sendMessage(id, request, currentUserProvider.requireUserId());
    }

    /**
     * 历史消息：时间正序返回；{@code cursor} 为上一页最旧一条消息 ID（用于继续加载更早消息）。
     */
    @GetMapping("/conversations/{id}/messages")
    public PageResult<DmMessageResponse> listMessages(@PathVariable String id,
                                                      @RequestParam(required = false) String cursor,
                                                      @RequestParam(required = false) Integer limit) {
        return dmService.listMessages(id, cursor, limit, currentUserProvider.requireUserId());
    }

    /**
     * 进入会话后标记已读：清零当前用户侧未读并通知对方。
     */
    @PostMapping("/conversations/{id}/read")
    public MarkReadResponse markRead(@PathVariable String id) {
        return dmService.markRead(id, currentUserProvider.requireUserId());
    }

    /**
     * 全站未读私信总数（侧边栏红点）。
     */
    @GetMapping("/unread-count")
    public UnreadCountResponse unreadCount() {
        return dmService.getUnreadCount(currentUserProvider.requireUserId());
    }
}
