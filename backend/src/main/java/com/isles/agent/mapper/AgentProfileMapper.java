package com.isles.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.agent.entity.AgentProfile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * AI 代理人设数据访问（V13）。
 */
@Mapper
public interface AgentProfileMapper extends BaseMapper<AgentProfile> {

    /** 调度用：所有 ACTIVE 的人设（含账号状态由调度器再校验）。 */
    @Select("SELECT * FROM agent_profiles WHERE status = 'ACTIVE' ORDER BY last_run_at IS NULL DESC, last_run_at ASC")
    List<AgentProfile> selectActive();

    @Select("SELECT * FROM agent_profiles WHERE user_id = #{userId}")
    AgentProfile selectByUserId(@Param("userId") String userId);

    @Select("SELECT COUNT(*) FROM agent_profiles")
    long countAll();
}
