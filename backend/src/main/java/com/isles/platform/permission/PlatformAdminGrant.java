package com.isles.platform.permission;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 平台管理员授权（表 {@code platform_admin_grants}，docs/11 §4.1）。
 *
 * <p>一个用户同时只保留一条授权；撤销即删除行。{@code users.platform_role='ADMIN'} 与环境变量白名单
 * 仅作为「引导超管」保留，日常授权走本表。
 */
@Getter
@Setter
@TableName("platform_admin_grants")
public class PlatformAdminGrant {

    @TableId(type = IdType.INPUT)
    private String id;
    private String userId;
    /** {@link PlatformRole} 名称。 */
    private String role;
    private String grantedBy;
    private LocalDateTime grantedAt;
    private LocalDateTime expiresAt;
}
