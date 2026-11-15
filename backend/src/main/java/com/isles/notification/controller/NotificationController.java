package com.isles.notification.controller;

import com.isles.common.pagination.PageResult;
import com.isles.common.security.CurrentUserProvider;
import com.isles.notification.dto.NotificationResponse;
import com.isles.notification.dto.UnreadCountResponse;
import com.isles.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通知接口（docs/05 §2.7 增强）：列表（频道/类型/分类/未读筛选）、未读数、已读、全部已读。
 */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping
    public PageResult<NotificationResponse> list(@RequestParam(required = false) String channelId,
                                                 @RequestParam(required = false) String type,
                                                 @RequestParam(required = false) String category,
                                                 @RequestParam(required = false, defaultValue = "false") boolean unreadOnly,
                                                 @RequestParam(required = false) String cursor,
                                                 @RequestParam(required = false) Integer limit) {
        return notificationService.list(currentUserProvider.requireUserId(), channelId, type, category,
                unreadOnly, cursor, limit);
    }

    @GetMapping("/unread-count")
    public UnreadCountResponse unreadCount() {
        return notificationService.unreadCount(currentUserProvider.requireUserId());
    }

    @PostMapping("/{id}/read")
    public NotificationResponse markRead(@PathVariable String id) {
        return notificationService.markRead(id, currentUserProvider.requireUserId());
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead(@RequestParam(required = false) String channelId,
                            @RequestParam(required = false) String category) {
        notificationService.markAllRead(currentUserProvider.requireUserId(), channelId, category);
    }
}
