package com.isles.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 频道助手自动任务执行日志（表 {@code ai_task_logs}，docs/03 FR-ASSIST-06）。
 *
 * <p>幂等键：channelId + taskType + frequencyKey（唯一约束），保证同一周期任务不重复执行。
 */
@Getter
@Setter
@TableName("ai_task_logs")
public class AiTaskLog {

    @TableId(type = IdType.INPUT)
    private String id;
    private String channelId;
    private String settingId;
    private String taskType;
    private String frequencyKey;
    private String status;
    private String resultText;
    private String result;
    private String postId;
    private String commentId;
    private String errorMessage;
    /** 本次任务实际使用的模型配置（V9）；配置被删除后置空，历史日志仍保留 {@code model} 快照 */
    private String modelConfigId;
    /** 模型 ID 快照（V9），来自 {@code ai_model_configs.model} */
    private String model;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;

    public enum TaskType {
        DAILY_SUMMARY, WEEKLY_SUMMARY, MONTHLY_CONTRIBUTORS, AUTO_POST, AUTO_COMMENT, AUTO_LIKE
    }

    public enum Status {
        RUNNING, SUCCESS, SKIPPED, FAILED
    }
}
