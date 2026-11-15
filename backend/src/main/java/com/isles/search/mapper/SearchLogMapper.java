package com.isles.search.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.search.entity.SearchLog;
import com.isles.search.mapper.row.KeywordCountRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 搜索日志数据访问。
 */
@Mapper
public interface SearchLogMapper extends BaseMapper<SearchLog> {

    /**
     * 按前缀匹配的高频搜索词（热词聚合为空时的回退实现）。
     */
    @Select("""
            SELECT keyword FROM search_logs
            WHERE keyword LIKE #{pattern}
            GROUP BY keyword
            ORDER BY COUNT(*) DESC, MAX(created_at) DESC
            LIMIT #{limit}
            """)
    List<String> selectPopularKeywords(@Param("pattern") String pattern, @Param("limit") int limit);

    /**
     * 热词聚合数据源：时间窗内按关键词统计次数与最近时间。
     */
    @Select("""
            SELECT keyword, COUNT(*) AS search_count, MAX(created_at) AS last_searched_at
            FROM search_logs
            WHERE created_at >= #{since}
            GROUP BY keyword
            ORDER BY search_count DESC, last_searched_at DESC
            LIMIT #{limit}
            """)
    List<KeywordCountRow> selectKeywordCounts(@Param("since") LocalDateTime since, @Param("limit") int limit);
}
