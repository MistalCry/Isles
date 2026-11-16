package com.isles.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * AI 代理运行日志（表 {@code agent_runs}，V13 / docs/12 §3.4）：一次 tick 一条，可回放决策与工具调用。
 */
@Getter
@Setter
@TableName("agent_runs")
public class AgentRun {

    @TableId(type = IdType.INPUT)
    private String id;
    private String agentId;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String planJson;
    private String toolCallsJson;
    private String outputType;
    private String outputId;
    private String status;
    private String error;
    private Integer tokensIn;
    private Integer tokensOut;

    public enum Status {
        SUCCESS, GUARDED, FAILED, SKIPPED
    }

    public enum OutputType {
        POST, COMMENT, LIKE, NONE
    }
}
