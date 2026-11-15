package com.isles.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务开关：数据中心日聚合、搜索热词聚合（任务本身幂等可重跑，见 docs/03 §8.3）。
 *
 * <p>独立配置类，避免与其他工作包（实时/AI）修改共享配置时冲突。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
