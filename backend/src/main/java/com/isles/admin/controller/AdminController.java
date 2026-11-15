package com.isles.admin.controller;

import com.isles.admin.dto.AdminDtos;
import com.isles.admin.service.AdminAuthService;
import com.isles.admin.service.AdminService;
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
 * 平台管理后台接口（docs/03 FR-PADM-02/03）：概览、用户/频道管理、举报处置。
 *
 * <p>除 {@code /admin/me} 外全部要求平台管理员身份（users.platform_role=ADMIN 或配置白名单）。
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminAuthService adminAuthService;
    private final AdminService adminService;

    /**
     * 当前登录用户的平台管理员身份（前端据此展示/隐藏后台入口；非管理员返回 admin=false）。
     */
    @GetMapping("/me")
    public AdminDtos.AdminIdentityResponse me() {
        return adminAuthService.identity();
    }

    @GetMapping("/overview")
    public AdminDtos.PlatformOverviewResponse overview() {
        adminAuthService.requireAdmin();
        return adminService.overview();
    }

    @GetMapping("/users")
    public AdminDtos.AdminUserPage users(@RequestParam(required = false) String keyword,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        adminAuthService.requireAdmin();
        return adminService.listUsers(keyword, status, page, size);
    }

    @PostMapping("/users/{id}/ban")
    public AdminDtos.AdminUserResponse banUser(@PathVariable String id) {
        adminAuthService.requireAdmin();
        return adminService.banUser(id);
    }

    @PostMapping("/users/{id}/unban")
    public AdminDtos.AdminUserResponse unbanUser(@PathVariable String id) {
        adminAuthService.requireAdmin();
        return adminService.unbanUser(id);
    }

    @GetMapping("/channels")
    public AdminDtos.AdminChannelPage channels(@RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer size) {
        adminAuthService.requireAdmin();
        return adminService.listChannels(keyword, status, page, size);
    }

    @PostMapping("/channels/{id}/ban")
    public AdminDtos.AdminChannelResponse banChannel(@PathVariable String id) {
        adminAuthService.requireAdmin();
        return adminService.banChannel(id);
    }

    @PostMapping("/channels/{id}/unban")
    public AdminDtos.AdminChannelResponse unbanChannel(@PathVariable String id) {
        adminAuthService.requireAdmin();
        return adminService.unbanChannel(id);
    }

    /**
     * 举报列表（依赖 reports 表：未就绪时 available=false 并返回依赖说明，前端展示提示条）。
     */
    @GetMapping("/reports")
    public AdminDtos.ReportPage reports(@RequestParam(required = false) String status,
                                        @RequestParam(required = false) Integer page,
                                        @RequestParam(required = false) Integer size) {
        adminAuthService.requireAdmin();
        return adminService.listReports(status, page, size);
    }

    @PostMapping("/reports/{id}/handle")
    public AdminDtos.HandleReportResponse handleReport(@PathVariable String id,
                                                       @Valid @RequestBody AdminDtos.HandleReportRequest request) {
        adminAuthService.requireAdmin();
        return adminService.handleReport(id, request.action(), request.note());
    }
}
