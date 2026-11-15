package com.isles.platform.penalty;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 用户处罚数据访问。
 *
 * <p>「生效中」= {@code status='ACTIVE'} 且未到期；到期不依赖定时任务，查询条件直接排除过期记录。
 */
@Mapper
public interface UserPenaltyMapper extends BaseMapper<UserPenalty> {

    @Select("""
            SELECT * FROM user_penalties
            WHERE user_id = #{userId} AND type = #{type} AND status = 'ACTIVE'
              AND (end_at IS NULL OR end_at > CURRENT_TIMESTAMP(3))
            ORDER BY created_at DESC
            LIMIT 1
            """)
    UserPenalty selectActive(@Param("userId") String userId, @Param("type") String type);

    @Select("""
            SELECT COUNT(*) FROM user_penalties
            WHERE user_id = #{userId} AND type = #{type} AND status = 'ACTIVE'
              AND (end_at IS NULL OR end_at > CURRENT_TIMESTAMP(3))
            """)
    long countActive(@Param("userId") String userId, @Param("type") String type);

    @Select("SELECT * FROM user_penalties WHERE user_id = #{userId} ORDER BY id DESC LIMIT #{limit}")
    List<UserPenalty> selectByUser(@Param("userId") String userId, @Param("limit") int limit);

    @Update("""
            UPDATE user_penalties
            SET status = 'REVOKED', revoked_by = #{operatorId}, revoked_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id} AND status = 'ACTIVE'
            """)
    int revoke(@Param("id") String id, @Param("operatorId") String operatorId);
}
