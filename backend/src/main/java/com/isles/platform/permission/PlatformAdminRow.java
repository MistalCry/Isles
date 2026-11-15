package com.isles.platform.permission;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 平台管理员列表行（授权表 JOIN users 的投影）。
 */
@Getter
@Setter
public class PlatformAdminRow {

    /** 授权 ID；引导超管（platform_role=ADMIN）为 null。 */
    private String id;
    private String userId;
    private String username;
    private String nickname;
    private String avatarUrl;
    private String role;
    private String grantedBy;
    private LocalDateTime grantedAt;
    private LocalDateTime expiresAt;
}
