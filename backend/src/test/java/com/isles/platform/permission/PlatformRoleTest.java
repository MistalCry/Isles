package com.isles.platform.permission;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 平台角色权限矩阵测试（docs/11 §7.1）：每种角色对关键权限点的取舍。
 */
class PlatformRoleTest {

    @Test
    void should_grant_every_permission_to_super_admin() {
        for (PlatformPermission permission : PlatformPermission.values()) {
            assertThat(PlatformRole.SUPER_ADMIN.has(permission))
                    .as("超级管理员应拥有 %s", permission)
                    .isTrue();
        }
    }

    @Test
    void should_forbid_operator_from_moderation_and_admin_management() {
        assertThat(PlatformRole.OPERATOR.has(PlatformPermission.CONFIG_WRITE)).isTrue();
        assertThat(PlatformRole.OPERATOR.has(PlatformPermission.AI_MANAGE)).isTrue();
        assertThat(PlatformRole.OPERATOR.has(PlatformPermission.ANNOUNCEMENT_MANAGE)).isTrue();
        assertThat(PlatformRole.OPERATOR.has(PlatformPermission.WORD_MANAGE)).isTrue();
        assertThat(PlatformRole.OPERATOR.has(PlatformPermission.REPORT_HANDLE)).isFalse();
        assertThat(PlatformRole.OPERATOR.has(PlatformPermission.USER_PENALTY)).isFalse();
        assertThat(PlatformRole.OPERATOR.has(PlatformPermission.ADMIN_MANAGE)).isFalse();
    }

    @Test
    void should_forbid_moderator_from_operations_management() {
        assertThat(PlatformRole.MODERATOR.has(PlatformPermission.ANNOUNCEMENT_MANAGE)).isFalse();
        assertThat(PlatformRole.MODERATOR.has(PlatformPermission.WORD_MANAGE)).isFalse();
    }

    @Test
    void should_forbid_moderator_from_config_write() {
        assertThat(PlatformRole.MODERATOR.has(PlatformPermission.CONFIG_READ)).isTrue();
        assertThat(PlatformRole.MODERATOR.has(PlatformPermission.REPORT_HANDLE)).isTrue();
        assertThat(PlatformRole.MODERATOR.has(PlatformPermission.CONTENT_TAKEDOWN)).isTrue();
        assertThat(PlatformRole.MODERATOR.has(PlatformPermission.CONFIG_WRITE)).isFalse();
        assertThat(PlatformRole.MODERATOR.has(PlatformPermission.ADMIN_MANAGE)).isFalse();
    }

    @Test
    void should_keep_viewer_read_only() {
        assertThat(PlatformRole.VIEWER.has(PlatformPermission.CONFIG_READ)).isTrue();
        assertThat(PlatformRole.VIEWER.has(PlatformPermission.AUDIT_READ)).isTrue();
        assertThat(PlatformRole.VIEWER.has(PlatformPermission.USER_READ)).isTrue();
        assertThat(PlatformRole.VIEWER.has(PlatformPermission.CONTENT_READ)).isTrue();
        assertThat(PlatformRole.VIEWER.has(PlatformPermission.REPORT_READ)).isTrue();
        assertThat(PlatformRole.VIEWER.has(PlatformPermission.CONFIG_WRITE)).isFalse();
        assertThat(PlatformRole.VIEWER.has(PlatformPermission.USER_BAN)).isFalse();
        assertThat(PlatformRole.VIEWER.has(PlatformPermission.CONTENT_TAKEDOWN)).isFalse();
    }

    @Test
    void should_let_operator_read_but_not_moderate() {
        assertThat(PlatformRole.OPERATOR.has(PlatformPermission.USER_READ)).isTrue();
        assertThat(PlatformRole.OPERATOR.has(PlatformPermission.CONTENT_READ)).isTrue();
        assertThat(PlatformRole.OPERATOR.has(PlatformPermission.REPORT_READ)).isTrue();
        assertThat(PlatformRole.OPERATOR.has(PlatformPermission.USER_BAN)).isFalse();
        assertThat(PlatformRole.OPERATOR.has(PlatformPermission.CONTENT_TAKEDOWN)).isFalse();
    }

    @Test
    void should_parse_role_name_case_insensitively() {
        assertThat(PlatformRole.fromName(" moderator ")).isEqualTo(PlatformRole.MODERATOR);
        assertThat(PlatformRole.fromName("unknown")).isNull();
        assertThat(PlatformRole.fromName(null)).isNull();
    }
}
