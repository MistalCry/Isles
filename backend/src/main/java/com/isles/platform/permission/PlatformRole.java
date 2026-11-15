package com.isles.platform.permission;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * 平台角色与权限点映射（docs/11 §7.1）。
 *
 * <p>固定四档角色：单人维护的项目不需要自定义角色，权限点已保留扩展空间。
 * 角色只影响后台，不影响频道内身份组（{@code roles.permissions}）。
 */
public enum PlatformRole {

    /** 超级管理员：全部权限，含管理员授权。 */
    SUPER_ADMIN("超级管理员", EnumSet.allOf(PlatformPermission.class)),

    /** 运营：配置、AI、公告、词库与数据；可查看用户/内容/举报但不能处置。 */
    OPERATOR("运营", EnumSet.of(
            PlatformPermission.CONFIG_READ,
            PlatformPermission.CONFIG_WRITE,
            PlatformPermission.AUDIT_READ,
            PlatformPermission.AI_MANAGE,
            PlatformPermission.ANNOUNCEMENT_MANAGE,
            PlatformPermission.WORD_MANAGE,
            PlatformPermission.USER_READ,
            PlatformPermission.CONTENT_READ,
            PlatformPermission.REPORT_READ,
            PlatformPermission.STATS_READ)),

    /** 审核：举报处置、内容下架、用户处罚；只读配置。 */
    MODERATOR("审核", EnumSet.of(
            PlatformPermission.CONFIG_READ,
            PlatformPermission.AUDIT_READ,
            PlatformPermission.USER_READ,
            PlatformPermission.CONTENT_READ,
            PlatformPermission.REPORT_READ,
            PlatformPermission.USER_BAN,
            PlatformPermission.USER_PENALTY,
            PlatformPermission.CONTENT_TAKEDOWN,
            PlatformPermission.REPORT_HANDLE,
            PlatformPermission.STATS_READ)),

    /** 只读：可查看配置、用户、内容、举报与审计，无任何写权限。 */
    VIEWER("只读", EnumSet.of(
            PlatformPermission.CONFIG_READ,
            PlatformPermission.AUDIT_READ,
            PlatformPermission.USER_READ,
            PlatformPermission.CONTENT_READ,
            PlatformPermission.REPORT_READ,
            PlatformPermission.STATS_READ));

    private final String label;
    private final Set<PlatformPermission> permissions;

    PlatformRole(String label, Set<PlatformPermission> permissions) {
        this.label = label;
        this.permissions = Set.copyOf(permissions);
    }

    public String label() {
        return label;
    }

    public Set<PlatformPermission> permissions() {
        return permissions;
    }

    public boolean has(PlatformPermission permission) {
        return permissions.contains(permission);
    }

    /**
     * 解析角色名；未知值返回 null（由调用方决定拒绝还是忽略）。
     */
    public static PlatformRole fromName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return Arrays.stream(values())
                .filter(role -> role.name().equalsIgnoreCase(name.trim()))
                .findFirst()
                .orElse(null);
    }
}
