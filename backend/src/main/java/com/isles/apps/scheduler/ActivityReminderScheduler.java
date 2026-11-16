package com.isles.apps.scheduler;

import com.isles.apps.service.ActivityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 活动提醒定时任务（V10）：每 10 分钟扫描一次「开始前 30 分钟」的已发布活动。
 *
 * <p>幂等由 {@code channel_activities.reminder_sent_at} 保证（先抢占再发通知），
 * 多实例同时扫描也只会有一个实例真正发送。扫描窗口 30 分钟 > 扫描间隔 10 分钟，不会漏。
 */
@Component
public class ActivityReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ActivityReminderScheduler.class);

    private final ActivityService activityService;

    public ActivityReminderScheduler(ActivityService activityService) {
        this.activityService = activityService;
    }

    @Scheduled(cron = "${app.apps.activity-reminder-cron:0 */10 * * * *}", zone = "UTC")
    public void scanAndRemind() {
        try {
            int reminded = activityService.remindDue();
            if (reminded > 0) {
                log.info("活动提醒扫描完成 reminded={}", reminded);
            }
        } catch (RuntimeException ex) {
            log.error("活动提醒扫描失败", ex);
        }
    }
}
