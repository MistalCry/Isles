package com.isles.platform.permission;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 平台管理员接口模型（docs/11 §5.3）。
 */
public final class PlatformAdminDtos {

    private PlatformAdminDtos() {
    }

    /**
     * @param bootstrap 是否来自 {@code users.platform_role=ADMIN} 的引导超管（不可在此撤销）
     */
    public record AdminView(
            String id,
            String userId,
            String username,
            String nickname,
            String avatarUrl,
            String role,
            String roleLabel,
            String grantedBy,
            Instant grantedAt,
            Instant expiresAt,
            boolean bootstrap) {
    }

    public record AdminListResponse(java.util.List<AdminView> items) {
    }

    public record GrantRequest(
            @NotBlank(message = "用户 ID 不能为空") String userId,
            @NotBlank(message = "角色不能为空")
            @Pattern(regexp = "SUPER_ADMIN|OPERATOR|MODERATOR|VIEWER", message = "角色取值非法")
            String role,
            Instant expiresAt) {
    }

    public record ChangeRoleRequest(
            @NotBlank(message = "角色不能为空")
            @Pattern(regexp = "SUPER_ADMIN|OPERATOR|MODERATOR|VIEWER", message = "角色取值非法")
            String role,
            Instant expiresAt,
            @Size(max = 200, message = "备注最长 200 个字符") String note) {
    }
}
