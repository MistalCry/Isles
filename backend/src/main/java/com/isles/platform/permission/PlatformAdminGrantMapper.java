package com.isles.platform.permission;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 平台管理员授权数据访问。
 *
 * <p>「生效中」= 未过期（{@code expires_at IS NULL OR expires_at > NOW()}）；
 * 撤销直接删除行，历史通过平台审计追溯。
 */
@Mapper
public interface PlatformAdminGrantMapper extends BaseMapper<PlatformAdminGrant> {

    @Select("""
            SELECT * FROM platform_admin_grants
            WHERE user_id = #{userId}
              AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP(3))
            """)
    PlatformAdminGrant selectActiveByUserId(@Param("userId") String userId);

    @Delete("DELETE FROM platform_admin_grants WHERE user_id = #{userId}")
    int deleteByUserId(@Param("userId") String userId);
}
