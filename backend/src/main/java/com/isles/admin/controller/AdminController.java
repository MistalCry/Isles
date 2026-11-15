package com.isles.admin.controller;

import com.isles.admin.dto.AdminDtos;
import com.isles.admin.service.AdminAuthService;
import com.isles.admin.service.AdminService;
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
 * 平台管理后台接口（docs/03 FR-PADM-02/03、docs/11 §5）。
 *
 * <p>权限由 {@code PlatformPermissionInterceptor} 按注解校验，不再在每个方法里手工调用；
 * {@code /admin/me} 供任意登录用户判断是否展示后台入口。
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminAuthService adminAuthService;
    private final AdminService adminService;

    /**
     * 当前登录用户的平台身份（前端据此展示/隐藏后台入口；无后台权限返回 admin=false）。
     */
    @GetMapping("/me")
    public AdminDtos.AdminIdentityResponse me() {
        return adminAuthService.identity();
    }

    @GetMapping("/overview")
    @RequirePlatformPermission(PlatformPermission.STATS_READ)
    public AdminDtos.PlatformOverviewResponse overview() {
        return adminService.overview();
    }

    @GetMapping("/users")
    @RequirePlatformPermission(PlatformPermission.USER_READ)
    public AdminDtos.AdminUserPage users(@RequestParam(required = false) String keyword,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        return adminService.listUsers(keyword, status, page, size);
    }

    @PostMapping("/users/{id}/ban")
    @RequirePlatformPermission(PlatformPermission.USER_BAN)
    public AdminDtos.AdminUserResponse banUser(@PathVariable String id) {
        return adminService.banUser(id);
    }

    @PostMapping("/users/{id}/unban")
    @RequirePlatformPermission(PlatformPermission.USER_BAN)
    public AdminDtos.AdminUserResponse unbanUser(@PathVariable String id) {
        return adminService.unbanUser(id);
    }

    @GetMapping("/channels")
    @RequirePlatformPermission(PlatformPermission.CONTENT_READ)
    public AdminDtos.AdminChannelPage channels(@RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer size) {
        return adminService.listChannels(keyword, status, page, size);
    }

    @PostMapping("/channels/{id}/ban")
    @RequirePlatformPermission(PlatformPermission.CONTENT_TAKEDOWN)
    public AdminDtos.AdminChannelResponse banChannel(@PathVariable String id) {
        return adminService.banChannel(id);
    }

    @PostMapping("/channels/{id}/unban")
    @RequirePlatformPermission(PlatformPermission.CONTENT_TAKEDOWN)
    public AdminDtos.AdminChannelResponse unbanChannel(@PathVariable String id) {
        return adminService.unbanChannel(id);
    }

    /**
     * 举报列表：支持状态、目标类型与关键字筛选（docs/11 §5.4）。
     */
    @GetMapping("/reports")
    @RequirePlatformPermission(PlatformPermission.REPORT_READ)
    public AdminDtos.ReportPage reports(@RequestParam(required = false) String status,
                                        @RequestParam(required = false) String targetType,
                                        @RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) Integer page,
                                        @RequestParam(required = false) Integer size) {
        return adminService.listReports(status, targetType, keyword, page, size);
    }

    @PostMapping("/reports/{id}/handle")
    @RequirePlatformPermission(PlatformPermission.REPORT_HANDLE)
    public AdminDtos.HandleReportResponse handleReport(@PathVariable String id,
                                                       @Valid @RequestBody AdminDtos.HandleReportRequest request) {
        return adminService.handleReport(id, request);
    }
}
