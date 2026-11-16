package com.isles.ai.dto;

import com.isles.ai.entity.AiTaskLog;
import com.isles.common.time.ClockTimes;

/**
 * 频道助手任务日志 → 响应模型映射（docs/03 FR-ASSIST-06）。
 *
 * <p>手动触发（{@code AiTaskService#runTask}）与日志列表（{@code ChannelAiSettingsService}）共用同一份映射，
 * 避免两处字段口径漂移。
 */
public final class AiTaskLogResponses {

    private AiTaskLogResponses() {
    }

    public static AiResponses.TaskLogResponse of(AiTaskLog taskLog) {
        if (taskLog == null) {
            return null;
        }
        return new AiResponses.TaskLogResponse(
                taskLog.getId(),
                taskLog.getChannelId(),
                taskLog.getTaskType(),
                taskLog.getFrequencyKey(),
                taskLog.getStatus(),
                taskLog.getResultText(),
                taskLog.getPostId(),
                taskLog.getCommentId(),
                taskLog.getErrorMessage(),
                ClockTimes.toInstant(taskLog.getStartedAt()),
                ClockTimes.toInstant(taskLog.getFinishedAt()),
                ClockTimes.toInstant(taskLog.getCreatedAt()),
                taskLog.getModelConfigId(),
                taskLog.getModel());
    }
}
