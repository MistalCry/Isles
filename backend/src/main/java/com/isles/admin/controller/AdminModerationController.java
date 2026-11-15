package com.isles.admin.controller;

import com.isles.admin.dto.AdminModerationDtos;
import com.isles.admin.service.AdminModerationService;
import com.isles.common.security.CurrentUserProvider;
import com.isles.platform.permission.PlatformPermission;
import com.isles.platform.permission.RequirePlatformPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台内容治理接口（docs/11 §5.4）：全站检索帖子/评论，下架与恢复。
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminModerationController {

    private final AdminModerationService moderationService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping("/posts")
    @RequirePlatformPermission(PlatformPermission.CONTENT_READ)
    public AdminModerationDtos.ContentPage posts(@RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) String channelId,
                                                 @RequestParam(required = false) String authorId,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(required = false) Integer page,
                                                 @RequestParam(required = false) Integer size) {
        return moderationService.listPosts(keyword, channelId, authorId, status, page, size);
    }

    @PostMapping("/posts/{id}/takedown")
    @RequirePlatformPermission(PlatformPermission.CONTENT_TAKEDOWN)
    public AdminModerationDtos.ContentActionResponse takedownPost(
            @PathVariable String id, @Valid @RequestBody AdminModerationDtos.TakedownRequest request) {
        return moderationService.takedownPost(id, request.reason(), currentUserProvider.requireUserId());
    }

    @PostMapping("/posts/{id}/restore")
    @RequirePlatformPermission(PlatformPermission.CONTENT_TAKEDOWN)
    public AdminModerationDtos.ContentActionResponse restorePost(@PathVariable String id) {
        return moderationService.restorePost(id, currentUserProvider.requireUserId());
    }

    @GetMapping("/comments")
    @RequirePlatformPermission(PlatformPermission.CONTENT_READ)
    public AdminModerationDtos.ContentPage comments(@RequestParam(required = false) String keyword,
                                                    @RequestParam(required = false) String channelId,
                                                    @RequestParam(required = false) String authorId,
                                                    @RequestParam(required = false) String status,
                                                    @RequestParam(required = false) Integer page,
                                                    @RequestParam(required = false) Integer size) {
        return moderationService.listComments(keyword, channelId, authorId, status, page, size);
    }

    @PostMapping("/comments/{id}/takedown")
    @RequirePlatformPermission(PlatformPermission.CONTENT_TAKEDOWN)
    public AdminModerationDtos.ContentActionResponse takedownComment(
            @PathVariable String id, @Valid @RequestBody AdminModerationDtos.TakedownRequest request) {
        return moderationService.takedownComment(id, request.reason(), currentUserProvider.requireUserId());
    }

    @PostMapping("/comments/{id}/restore")
    @RequirePlatformPermission(PlatformPermission.CONTENT_TAKEDOWN)
    public AdminModerationDtos.ContentActionResponse restoreComment(@PathVariable String id) {
        return moderationService.restoreComment(id, currentUserProvider.requireUserId());
    }
}
