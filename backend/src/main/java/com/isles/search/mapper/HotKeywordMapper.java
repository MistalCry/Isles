package com.isles.search.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.search.entity.HotKeyword;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 搜索热词数据访问：绝对值覆盖写入（可重复聚合），前缀匹配按搜索次数排序。
 */
@Mapper
public interface HotKeywordMapper extends BaseMapper<HotKeyword> {

    @Insert("""
            INSERT INTO hot_keywords (id, keyword, search_count, last_searched_at, created_at, updated_at)
            VALUES (#{id}, #{keyword}, #{searchCount}, #{lastSearchedAt}, #{createdAt}, CURRENT_TIMESTAMP(3))
            ON DUPLICATE KEY UPDATE
                search_count = VALUES(search_count),
                last_searched_at = VALUES(last_searched_at),
                updated_at = CURRENT_TIMESTAMP(3)
            """)
    int upsert(HotKeyword keyword);

    @Select("""
            SELECT * FROM hot_keywords
            WHERE keyword LIKE #{pattern}
            ORDER BY search_count DESC, keyword ASC
            LIMIT #{limit}
            """)
    List<HotKeyword> selectByPrefix(@Param("pattern") String pattern, @Param("limit") int limit);

    @Select("""
            SELECT * FROM hot_keywords
            ORDER BY search_count DESC, keyword ASC
            LIMIT #{limit}
            """)
    List<HotKeyword> selectTop(@Param("limit") int limit);
}
