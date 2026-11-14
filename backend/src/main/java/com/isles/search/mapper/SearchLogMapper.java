package com.isles.search.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.search.entity.SearchLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 搜索日志数据访问。
 */
@Mapper
public interface SearchLogMapper extends BaseMapper<SearchLog> {

    /**
     * 按前缀匹配的高频搜索词（M1 联想的基础实现；热词聚合完整版在 M2）。
     */
    @Select("""
            SELECT keyword FROM search_logs
            WHERE keyword LIKE #{pattern}
            GROUP BY keyword
            ORDER BY COUNT(*) DESC, MAX(created_at) DESC
            LIMIT #{limit}
            """)
    List<String> selectPopularKeywords(@Param("pattern") String pattern, @Param("limit") int limit);
}
