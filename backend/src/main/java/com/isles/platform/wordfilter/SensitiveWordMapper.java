package com.isles.platform.wordfilter;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 敏感词数据访问。
 */
@Mapper
public interface SensitiveWordMapper extends BaseMapper<SensitiveWord> {

    @Select("""
            <script>
            SELECT * FROM sensitive_words
            <where>
                <if test="pattern != null">AND word LIKE #{pattern}</if>
            </where>
            ORDER BY created_at DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<SensitiveWord> selectPage(@Param("pattern") String pattern,
                                   @Param("limit") int limit,
                                   @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM sensitive_words
            <where>
                <if test="pattern != null">AND word LIKE #{pattern}</if>
            </where>
            </script>
            """)
    long countPage(@Param("pattern") String pattern);

    /**
     * 加载启用中的词条（匹配缓存使用）。
     */
    @Select("SELECT * FROM sensitive_words WHERE enabled = 1")
    List<SensitiveWord> selectEnabled();

    @Select("SELECT COUNT(*) FROM sensitive_words WHERE word = #{word}")
    long countByWord(@Param("word") String word);
}
