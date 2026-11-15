package com.isles.admin.mapper;

import com.isles.admin.dto.AdminUserRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 平台后台用户查询：读取 users.platform_role（V3 新增列）与平台治理字段。
 *
 * <p>不修改用户模块实体，平台角色只在本模块 SQL 中显式选取。
 */
@Mapper
public interface AdminUserMapper {

    @Select("""
            SELECT id, username, nickname, avatar_url AS avatarUrl, signature, status,
                   platform_role AS platformRole, created_at AS createdAt
            FROM users
            WHERE id = #{id} AND deleted_at IS NULL
            """)
    AdminUserRow selectRowById(@Param("id") String id);

    @Select("""
            <script>
            SELECT id, username, nickname, avatar_url AS avatarUrl, signature, status,
                   platform_role AS platformRole, created_at AS createdAt
            FROM users
            WHERE deleted_at IS NULL
            <if test="pattern != null">AND (username LIKE #{pattern} OR nickname LIKE #{pattern})</if>
            <if test="status != null">AND status = #{status}</if>
            ORDER BY id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<AdminUserRow> selectPage(@Param("pattern") String pattern,
                                  @Param("status") String status,
                                  @Param("limit") int limit,
                                  @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM users
            WHERE deleted_at IS NULL
            <if test="pattern != null">AND (username LIKE #{pattern} OR nickname LIKE #{pattern})</if>
            <if test="status != null">AND status = #{status}</if>
            </script>
            """)
    long countPage(@Param("pattern") String pattern, @Param("status") String status);

    @Update("""
            UPDATE users SET status = #{status}, updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id} AND deleted_at IS NULL
            """)
    int updateStatus(@Param("id") String id, @Param("status") String status);
}
