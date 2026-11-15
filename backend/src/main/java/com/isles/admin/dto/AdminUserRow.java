package com.isles.admin.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 平台后台用户行（含 platform_role，User 实体不映射该列以保持用户模块不变）。
 */
@Getter
@Setter
public class AdminUserRow {

    private String id;
    private String username;
    private String nickname;
    private String avatarUrl;
    private String signature;
    private String status;
    private String platformRole;
    private LocalDateTime createdAt;
}
