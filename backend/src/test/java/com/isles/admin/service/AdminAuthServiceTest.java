package com.isles.admin.service;

import com.isles.admin.dto.AdminDtos;
import com.isles.admin.dto.AdminUserRow;
import com.isles.admin.mapper.AdminUserMapper;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 平台管理员鉴权单元测试（docs/03 FR-PADM、docs/02 §5.3）：
 * platform_role=ADMIN、配置白名单、普通用户 403、身份查询不抛错。
 */
class AdminAuthServiceTest {

    private AdminUserMapper adminUserMapper;
    private CurrentUserProvider currentUserProvider;
    private AdminAuthService service;

    @BeforeEach
    void setUp() {
        adminUserMapper = mock(AdminUserMapper.class);
        currentUserProvider = mock(CurrentUserProvider.class);
        when(currentUserProvider.requireUserId()).thenReturn("user-1");
        service = new AdminAuthService(adminUserMapper, currentUserProvider, "boss,operator");
    }

    @Test
    void should_allow_admin_by_platform_role() {
        when(adminUserMapper.selectRowById("user-1")).thenReturn(row("user-1", "alice", "ADMIN"));

        assertThat(service.isAdmin("user-1")).isTrue();
        assertThat(service.requireAdmin()).isEqualTo("user-1");
    }

    @Test
    void should_allow_admin_by_configured_username_whitelist() {
        when(adminUserMapper.selectRowById("user-1")).thenReturn(row("user-1", "boss", "USER"));

        assertThat(service.isAdmin("user-1")).isTrue();
    }

    @Test
    void should_forbid_normal_user() {
        when(adminUserMapper.selectRowById("user-1")).thenReturn(row("user-1", "joe", "USER"));

        assertThat(service.isAdmin("user-1")).isFalse();
        assertThatThrownBy(() -> service.requireAdmin())
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void should_return_identity_for_sidebar_usage() {
        when(adminUserMapper.selectRowById("user-1")).thenReturn(row("user-1", "joe", "USER"));

        AdminDtos.AdminIdentityResponse identity = service.identity();

        assertThat(identity.admin()).isFalse();
        assertThat(identity.username()).isEqualTo("joe");
        assertThat(identity.platformRole()).isEqualTo("USER");
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
