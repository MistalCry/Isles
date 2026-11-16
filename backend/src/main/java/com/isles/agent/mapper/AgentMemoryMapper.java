package com.isles.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.agent.entity.AgentMemory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 代理记忆数据访问（V13）。
 */
@Mapper
public interface AgentMemoryMapper extends BaseMapper<AgentMemory> {

    /** 最近 N 条某类记忆（去重与提示词都用它）。 */
    @Select("""
            SELECT * FROM agent_memories
            WHERE agent_id = #{agentId} AND kind = #{kind} AND created_at >= #{since}
            ORDER BY created_at DESC
            LIMIT #{limit}
            """)
    List<AgentMemory> selectRecent(@Param("agentId") String agentId, @Param("kind") String kind,
                                   @Param("since") LocalDateTime since, @Param("limit") int limit);

    /** 指纹是否已存在（同一代理、窗口内）。 */
    @Select("""
            SELECT COUNT(*) FROM agent_memories
            WHERE agent_id = #{agentId} AND fingerprint = #{fingerprint} AND created_at >= #{since}
            """)
    long countByFingerprint(@Param("agentId") String agentId, @Param("fingerprint") String fingerprint,
                            @Param("since") LocalDateTime since);

    /** recall：按关键词模糊匹配记忆正文。 */
    @Select("""
            <script>
            SELECT * FROM agent_memories
            WHERE agent_id = #{agentId}
            <if test="patterns != null and patterns.size() > 0">
              AND
              <foreach item="kw" collection="patterns" open="(" separator=" OR " close=")">
                content LIKE #{kw}
              </foreach>
            </if>
            ORDER BY created_at DESC
            LIMIT #{limit}
            </script>
            """)
    List<AgentMemory> search(@Param("agentId") String agentId, @Param("patterns") List<String> patterns,
                             @Param("limit") int limit);
}
