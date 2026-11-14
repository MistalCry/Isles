package com.isles.channel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.channel.entity.Board;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 板块数据访问。
 */
@Mapper
public interface BoardMapper extends BaseMapper<Board> {

    @Update("""
            UPDATE boards
            SET post_count = GREATEST(post_count + #{delta}, 0), updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id}
            """)
    int addPostCount(@Param("id") String id, @Param("delta") int delta);
}
