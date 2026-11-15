package com.isles.admin.controller;

import com.isles.common.security.CurrentUserProvider;
import com.isles.platform.announcement.AnnouncementDtos;
import com.isles.platform.announcement.AnnouncementService;
import com.isles.platform.permission.PlatformPermission;
import com.isles.platform.permission.RequirePlatformPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台公告管理接口（docs/11 §5.5）：草稿、编辑、发布、归档、删除。
 */
@RestController
@RequestMapping("/api/v1/admin/announcements")
@RequiredArgsConstructor
public class AdminAnnouncementController {

    private final AnnouncementService announcementService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping
    @RequirePlatformPermission(PlatformPermission.CONFIG_READ)
    public AnnouncementDtos.AnnouncementPage list(@RequestParam(required = false) String status,
                                                  @RequestParam(required = false) Integer page,
                                                  @RequestParam(required = false) Integer size) {
        return announcementService.list(status, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePlatformPermission(PlatformPermission.ANNOUNCEMENT_MANAGE)
    public AnnouncementDtos.AnnouncementView create(
            @Valid @RequestBody AnnouncementDtos.SaveAnnouncementRequest request) {
        return announcementService.create(request, currentUserProvider.requireUserId());
    }

    @PutMapping("/{id}")
    @RequirePlatformPermission(PlatformPermission.ANNOUNCEMENT_MANAGE)
    public AnnouncementDtos.AnnouncementView update(
            @PathVariable String id, @Valid @RequestBody AnnouncementDtos.SaveAnnouncementRequest request) {
        return announcementService.update(id, request, currentUserProvider.requireUserId());
    }

    @PostMapping("/{id}/publish")
    @RequirePlatformPermission(PlatformPermission.ANNOUNCEMENT_MANAGE)
    public AnnouncementDtos.AnnouncementView publish(@PathVariable String id) {
        return announcementService.publish(id, currentUserProvider.requireUserId());
    }

    @PostMapping("/{id}/archive")
    @RequirePlatformPermission(PlatformPermission.ANNOUNCEMENT_MANAGE)
    public AnnouncementDtos.AnnouncementView archive(@PathVariable String id) {
        return announcementService.archive(id, currentUserProvider.requireUserId());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePlatformPermission(PlatformPermission.ANNOUNCEMENT_MANAGE)
    public void delete(@PathVariable String id) {
        announcementService.delete(id, currentUserProvider.requireUserId());
    }
}
