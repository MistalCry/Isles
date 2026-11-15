package com.isles.admin.service;

import com.isles.admin.dto.AdminDtos;
import com.isles.admin.dto.AdminUserRow;
import com.isles.admin.mapper.AdminUserMapper;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.security.CurrentUserProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 平台管理员鉴权（docs/03 FR-PADM、docs/02 §5.3）。
 *
 * <p>判定：{@code users.platform_role = 'ADMIN'}，或用户名命中配置白名单
 * {@code app.platform-admin-usernames}（逗号分隔，便于本地/应急提权）。两者都没有则 403。
 */
@Service
public class AdminAuthService {

    private static final String ADMIN_ROLE = "ADMIN";

    private final AdminUserMapper adminUserMapper;
    private final CurrentUserProvider currentUserProvider;
    private final Set<String> whitelist;

    public AdminAuthService(AdminUserMapper adminUserMapper,
                            CurrentUserProvider currentUserProvider,
                            @Value("${app.platform-admin-usernames:}") String whitelist) {
        this.adminUserMapper = adminUserMapper;
        this.currentUserProvider = currentUserProvider;
        this.whitelist = Arrays.stream(whitelist == null ? new String[0] : whitelist.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 要求平台管理员身份，返回当前管理员 userId。
     */
    public String requireAdmin() {
        String userId = currentUserProvider.requireUserId();
        if (!isAdmin(userId)) {
            throw new AppException(ErrorCode.FORBIDDEN, "需要平台管理员权限");
        }
        return userId;
    }

    public boolean isAdmin(String userId) {
        AdminUserRow row = adminUserMapper.selectRowById(userId);
        if (row == null) {
            return false;
        }
        if (ADMIN_ROLE.equalsIgnoreCase(row.getPlatformRole())) {
            return true;
        }
        return row.getUsername() != null && whitelist.contains(row.getUsername());
    }

    /**
     * 当前身份（任意登录用户可调用）：前端据此决定是否展示平台后台入口。
     */
    public AdminDtos.AdminIdentityResponse identity() {
        String userId = currentUserProvider.requireUserId();
        AdminUserRow row = adminUserMapper.selectRowById(userId);
        if (row == null) {
            throw new AppException(ErrorCode.USER_NOT_FOUND);
        }
        boolean admin = ADMIN_ROLE.equalsIgnoreCase(row.getPlatformRole()) || whitelist.contains(row.getUsername());
        return new AdminDtos.AdminIdentityResponse(admin, row.getId(), row.getUsername(), row.getPlatformRole());
    }
}
