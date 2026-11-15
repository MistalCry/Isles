package com.isles.channel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.feed.entity.ChannelRecommendation;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 频道推荐数据访问：按用户存储规则打分结果，唯一键 (user_id, channel_id) + upsert。
 */
@Mapper
public interface ChannelRecommendationMapper extends BaseMapper<ChannelRecommendation> {

    @Insert("""
            INSERT INTO channel_recommendations (id, user_id, channel_id, score, reason, generated_at)
            VALUES (#{id}, #{userId}, #{channelId}, #{score}, #{reason}, #{generatedAt})
            ON DUPLICATE KEY UPDATE
                score = VALUES(score),
                reason = VALUES(reason),
                generated_at = VALUES(generated_at)
            """)
    int upsert(ChannelRecommendation recommendation);

    @Select("""
            SELECT * FROM channel_recommendations
            WHERE user_id = #{userId}
            ORDER BY score DESC
            LIMIT #{limit}
            """)
    List<ChannelRecommendation> selectByUser(@Param("userId") String userId, @Param("limit") int limit);
}
