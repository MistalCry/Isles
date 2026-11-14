package com.isles.search.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.search.entity.SearchHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 搜索历史数据访问。
 */
@Mapper
public interface SearchHistoryMapper extends BaseMapper<SearchHistory> {

    /**
     * 最近搜索历史（最多 20 条，避免无界查询）。
     */
    @Select("""
            SELECT * FROM search_history
            WHERE user_id = #{userId}
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<SearchHistory> selectRecent(@Param("userId") String userId, @Param("limit") int limit);
}
