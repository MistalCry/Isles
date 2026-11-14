package com.isles.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.auth.entity.RefreshToken;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 刷新令牌数据访问。
 */
@Mapper
public interface RefreshTokenMapper extends BaseMapper<RefreshToken> {

    /**
     * 撤销某用户全部未撤销令牌（登出 / 安全事件场景）。
     */
    @Update("""
            UPDATE refresh_tokens
            SET revoked_at = CURRENT_TIMESTAMP(3)
            WHERE user_id = #{userId} AND revoked_at IS NULL
            """)
    int revokeAllActiveByUser(@Param("userId") String userId);

    /**
     * 撤销单个令牌（轮换时旧令牌立即失效）。
     */
    @Update("""
            UPDATE refresh_tokens
            SET revoked_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id} AND revoked_at IS NULL
            """)
    int revokeById(@Param("id") String id);
}
