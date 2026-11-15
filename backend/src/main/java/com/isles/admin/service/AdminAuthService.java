package com.isles.admin.service;

import com.isles.admin.dto.AdminDtos;
import com.isles.admin.dto.AdminUserRow;
import com.isles.admin.mapper.AdminUserMapper;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.security.CurrentUserProvider;
import com.isles.platform.permission.PlatformAdminService;
import com.isles.platform.permission.PlatformRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 平台管理员身份查询（docs/11 §7）。
 *
 * <p>角色解析统一委托 {@link PlatformAdminService}（授权表 → {@code users.platform_role} → 环境变量白名单），
 * 本类只负责「当前登录用户的身份展示」与兼容旧调用方的 {@code requireAdmin}。
 */
@Service
@RequiredArgsConstructor
public class AdminAuthService {

    private final AdminUserMapper adminUserMapper;
    private final CurrentUserProvider currentUserProvider;
    private final PlatformAdminService platformAdminService;

    /**
     * 要求平台管理员身份（任意角色），返回当前管理员 userId。
     */
    public String requireAdmin() {
        return platformAdminService.requireAdmin();
    }

    public boolean isAdmin(String userId) {
        return platformAdminService.resolveRole(userId) != null;
    }

    /**
     * 当前身份（任意登录用户可调用）：前端据此决定后台入口与菜单可见性。
     */
    public AdminDtos.AdminIdentityResponse identity() {
        String userId = currentUserProvider.requireUserId();
        AdminUserRow row = adminUserMapper.selectRowById(userId);
        if (row == null) {
            throw new AppException(ErrorCode.USER_NOT_FOUND);
        }
        PlatformRole role = platformAdminService.resolveRole(userId);
        return new AdminDtos.AdminIdentityResponse(role != null, row.getId(), row.getUsername(),
                role == null ? "USER" : role.name(),
                role == null ? List.of() : role.permissions().stream().map(Enum::name).sorted().toList());
    }
}
