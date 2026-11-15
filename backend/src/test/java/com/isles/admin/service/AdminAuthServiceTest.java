package com.isles.admin.service;

import com.isles.admin.dto.AdminDtos;
import com.isles.admin.dto.AdminUserRow;
import com.isles.admin.mapper.AdminUserMapper;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.security.CurrentUserProvider;
import com.isles.platform.audit.PlatformAuditService;
import com.isles.platform.permission.PlatformAdminGrant;
import com.isles.platform.permission.PlatformAdminGrantMapper;
import com.isles.platform.permission.PlatformAdminQueryMapper;
import com.isles.platform.permission.PlatformAdminService;
import com.isles.user.entity.User;
import com.isles.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 平台管理员鉴权单元测试（docs/11 §7）：授权表、platform_role、配置白名单、普通用户 403。
 *
 * <p>AdminAuthService 已委托 {@link PlatformAdminService}，这里连同角色解析一起验证。
 */
class AdminAuthServiceTest {

    private AdminUserMapper adminUserMapper;
    private PlatformAdminGrantMapper grantMapper;
    private PlatformAdminQueryMapper queryMapper;
    private UserMapper userMapper;
    private CurrentUserProvider currentUserProvider;
    private AdminAuthService service;

    @BeforeEach
    void setUp() {
        adminUserMapper = mock(AdminUserMapper.class);
        grantMapper = mock(PlatformAdminGrantMapper.class);
        queryMapper = mock(PlatformAdminQueryMapper.class);
        userMapper = mock(UserMapper.class);
        currentUserProvider = mock(CurrentUserProvider.class);
        when(currentUserProvider.requireUserId()).thenReturn("user-1");
        PlatformAdminService platformAdminService = new PlatformAdminService(grantMapper, queryMapper, userMapper,
                currentUserProvider, mock(PlatformAuditService.class), "boss,operator");
        service = new AdminAuthService(adminUserMapper, currentUserProvider, platformAdminService);
    }

    @Test
    void should_allow_admin_by_platform_role() {
        when(userMapper.selectById("user-1")).thenReturn(user("user-1", "alice", "ADMIN"));

        assertThat(service.isAdmin("user-1")).isTrue();
        assertThat(service.requireAdmin()).isEqualTo("user-1");
    }

    @Test
    void should_allow_admin_by_configured_username_whitelist() {
        when(userMapper.selectById("user-1")).thenReturn(user("user-1", "boss", "USER"));

        assertThat(service.isAdmin("user-1")).isTrue();
    }

    @Test
    void should_resolve_granted_role_before_legacy_flag() {
        when(grantMapper.selectActiveByUserId("user-1")).thenReturn(grant("user-1", "MODERATOR"));
        when(adminUserMapper.selectRowById("user-1")).thenReturn(row("user-1", "joe", "USER"));

        AdminDtos.AdminIdentityResponse identity = service.identity();

        assertThat(identity.admin()).isTrue();
        assertThat(identity.platformRole()).isEqualTo("MODERATOR");
        assertThat(identity.permissions()).contains("REPORT_HANDLE", "CONTENT_TAKEDOWN")
                .doesNotContain("CONFIG_WRITE");
    }

    @Test
    void should_forbid_normal_user() {
        when(userMapper.selectById("user-1")).thenReturn(user("user-1", "joe", "USER"));

        assertThat(service.isAdmin("user-1")).isFalse();
        assertThatThrownBy(() -> service.requireAdmin())
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void should_return_identity_for_sidebar_usage() {
        when(userMapper.selectById("user-1")).thenReturn(user("user-1", "joe", "USER"));
        when(adminUserMapper.selectRowById("user-1")).thenReturn(row("user-1", "joe", "USER"));

        AdminDtos.AdminIdentityResponse identity = service.identity();

        assertThat(identity.admin()).isFalse();
        assertThat(identity.username()).isEqualTo("joe");
        assertThat(identity.platformRole()).isEqualTo("USER");
    }

    private User user(String id, String username, String platformRole) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setNickname(username);
        user.setStatus("ACTIVE");
        user.setPlatformRole(platformRole);
        return user;
    }

    private PlatformAdminGrant grant(String userId, String role) {
        PlatformAdminGrant grant = new PlatformAdminGrant();
        grant.setId("grant-1");
        grant.setUserId(userId);
        grant.setRole(role);
        return grant;
    }

    private AdminUserRow row(String id, String username, String role) {
        AdminUserRow row = new AdminUserRow();
        row.setId(id);
        row.setUsername(username);
        row.setPlatformRole(role);
        row.setStatus("ACTIVE");
        return row;
    }
}
