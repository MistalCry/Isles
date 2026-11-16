package com.isles.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.agent.entity.AgentRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 代理运行日志数据访问（V13）。
 */
@Mapper
public interface AgentRunMapper extends BaseMapper<AgentRun> {

    /** 某代理某类产出在窗口内的条数（日上限校验）。 */
    @Select("""
            SELECT COUNT(*) FROM agent_runs
            WHERE agent_id = #{agentId} AND output_type = #{outputType} AND status = 'SUCCESS'
              AND started_at >= #{since}
            """)
    long countOutputSince(@Param("agentId") String agentId, @Param("outputType") String outputType,
                          @Param("since") LocalDateTime since);

    /** 某代理最后一次成功产出该类内容的时间（发帖间隔校验）。 */
    @Select("""
            SELECT MAX(started_at) FROM agent_runs
            WHERE agent_id = #{agentId} AND output_type = #{outputType} AND status = 'SUCCESS'
            """)
    LocalDateTime lastOutputAt(@Param("agentId") String agentId, @Param("outputType") String outputType);

    /** 全平台某类产出在窗口内的条数（平台级日上限）。 */
    @Select("""
            SELECT COUNT(*) FROM agent_runs
            WHERE output_type = #{outputType} AND status = 'SUCCESS' AND started_at >= #{since}
            """)
    long countOutputSinceAll(@Param("outputType") String outputType, @Param("since") LocalDateTime since);

    /** 平台级当日 token 消耗（成本熔断）。 */
    @Select("SELECT COALESCE(SUM(tokens_in + tokens_out), 0) FROM agent_runs WHERE started_at >= #{since}")
    long sumTokensSince(@Param("since") LocalDateTime since);

    /** 某代理的运行日志分页。 */
    @Select("""
            SELECT * FROM agent_runs
            WHERE agent_id = #{agentId}
            ORDER BY started_at DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<AgentRun> selectPage(@Param("agentId") String agentId, @Param("limit") int limit,
                              @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM agent_runs WHERE agent_id = #{agentId}")
    long countByAgent(@Param("agentId") String agentId);
}
