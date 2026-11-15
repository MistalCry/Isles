package com.isles.ai.service;

import com.isles.ai.config.AiProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 频道助手自动任务调度器（docs/03 FR-ASSIST-06、§6.6）。
 *
 * <p>默认每小时扫描一次（{@code app.ai.task-cron}），由 {@link AiTaskService} 判断各频道
 * 是否到点、开关是否开启、是否超过每日频率上限；{@code app.ai.scheduler-enabled=false} 时任务体直接返回。
 * 调度线程异常被捕获在任务服务内部，不会影响后续调度。
 */
@Component
@RequiredArgsConstructor
public class AiTaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(AiTaskScheduler.class);

    private final AiTaskService taskService;
    private final AiProperties properties;

    @Scheduled(cron = "${app.ai.task-cron:0 0 * * * *}", zone = "UTC")
    public void scanChannelAssistantTasks() {
        if (!properties.schedulerEnabled()) {
            return;
        }
        try {
            taskService.scanAndRun();
        } catch (RuntimeException ex) {
            // 调度层兜底：任何异常都不能让定时线程退出
            log.error("频道助手任务调度异常", ex);
        }
    }
}
