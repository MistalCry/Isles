package com.isles.platform.permission;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 平台管理员列表查询：授权表与 users 一次性 JOIN，避免逐条查用户。
 */
@Mapper
public interface PlatformAdminQueryMapper {

    @Select("""
            SELECT g.id, g.user_id AS userId, u.username, u.nickname, u.avatar_url AS avatarUrl,
                   g.role, g.granted_by AS grantedBy, g.granted_at AS grantedAt, g.expires_at AS expiresAt
            FROM platform_admin_grants g
            JOIN users u ON u.id = g.user_id
            ORDER BY g.granted_at DESC
            """)
    List<PlatformAdminRow> selectGrantRows();

    /**
     * 引导超管：users.platform_role='ADMIN' 且未被授权表覆盖的账号（兼容 V3 的提权方式）。
     */
    @Select("""
            SELECT NULL AS id, u.id AS userId, u.username, u.nickname, u.avatar_url AS avatarUrl,
                   'SUPER_ADMIN' AS role, NULL AS grantedBy, NULL AS grantedAt, NULL AS expiresAt
            FROM users u
            WHERE u.platform_role = 'ADMIN' AND u.deleted_at IS NULL
              AND NOT EXISTS (SELECT 1 FROM platform_admin_grants g WHERE g.user_id = u.id)
            ORDER BY u.created_at
            """)
    List<PlatformAdminRow> selectLegacyRows();

    /**
     * 生效中的超级管理员总数（去重）：正式授权的 SUPER_ADMIN 与引导超管可能重叠，按用户去重。
     */
    @Select("""
            SELECT COUNT(DISTINCT u.id)
            FROM users u
            LEFT JOIN platform_admin_grants g
                   ON g.user_id = u.id AND (g.expires_at IS NULL OR g.expires_at > CURRENT_TIMESTAMP(3))
            WHERE (u.platform_role = 'ADMIN' AND u.deleted_at IS NULL)
               OR (g.role = 'SUPER_ADMIN')
            """)
    long countActiveSuperAdmins();
}
