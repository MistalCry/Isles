package com.isles.apps.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.apps.entity.ActivitySignup;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 活动报名数据访问（V10）。
 */
@Mapper
public interface ActivitySignupMapper extends BaseMapper<ActivitySignup> {

    @Select("""
            SELECT * FROM activity_signups
            WHERE activity_id = #{activityId} AND status = 'GOING'
            ORDER BY created_at ASC
            """)
    List<ActivitySignup> selectGoing(@Param("activityId") String activityId);

    @Select("SELECT COUNT(*) FROM activity_signups WHERE activity_id = #{activityId} AND status = 'GOING'")
    long countGoing(@Param("activityId") String activityId);

    @Select("SELECT * FROM activity_signups WHERE activity_id = #{activityId} AND user_id = #{userId}")
    ActivitySignup selectByActivityAndUser(@Param("activityId") String activityId, @Param("userId") String userId);
}
