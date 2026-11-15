package com.isles.admin.controller;

import com.isles.common.security.CurrentUserProvider;
import com.isles.platform.permission.PlatformAdminDtos;
import com.isles.platform.permission.PlatformAdminService;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台管理员授权接口（docs/11 §5.3）。
 *
 * <p>仅超级管理员可操作；不能撤销自己，也不能撤销最后一个超级管理员（由服务层校验）。
 */
@RestController
@RequestMapping("/api/v1/admin/admins")
@RequiredArgsConstructor
public class AdminAdminController {

    private final PlatformAdminService adminService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping
    @RequirePlatformPermission(PlatformPermission.ADMIN_MANAGE)
    public PlatformAdminDtos.AdminListResponse list() {
        List<PlatformAdminDtos.AdminView> items = adminService.list();
        return new PlatformAdminDtos.AdminListResponse(items);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePlatformPermission(PlatformPermission.ADMIN_MANAGE)
    public PlatformAdminDtos.AdminView grant(@Valid @RequestBody PlatformAdminDtos.GrantRequest request) {
        return adminService.grant(request, currentUserProvider.requireUserId());
    }

    @PutMapping("/{userId}")
    @RequirePlatformPermission(PlatformPermission.ADMIN_MANAGE)
    public PlatformAdminDtos.AdminView changeRole(@PathVariable String userId,
                                                  @Valid @RequestBody PlatformAdminDtos.ChangeRoleRequest request) {
        return adminService.changeRole(userId, request, currentUserProvider.requireUserId());
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePlatformPermission(PlatformPermission.ADMIN_MANAGE)
    public void revoke(@PathVariable String userId) {
        adminService.revoke(userId, currentUserProvider.requireUserId());
    }
}
