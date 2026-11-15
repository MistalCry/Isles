package com.isles.platform.wordfilter;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 敏感词命中记录数据访问（只追加）。
 */
@Mapper
public interface SensitiveWordHitMapper extends BaseMapper<SensitiveWordHit> {

    @Select("""
            <script>
            SELECT * FROM sensitive_word_hits
            <where>
                <if test="scene != null">AND scene = #{scene}</if>
                <if test="word != null">AND word = #{word}</if>
            </where>
            ORDER BY id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<SensitiveWordHit> selectPage(@Param("scene") String scene,
                                      @Param("word") String word,
                                      @Param("limit") int limit,
                                      @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM sensitive_word_hits
            <where>
                <if test="scene != null">AND scene = #{scene}</if>
                <if test="word != null">AND word = #{word}</if>
            </where>
            </script>
            """)
    long countPage(@Param("scene") String scene, @Param("word") String word);
}
