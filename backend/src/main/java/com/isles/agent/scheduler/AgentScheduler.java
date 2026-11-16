package com.isles.agent.scheduler;

import com.isles.agent.service.AgentRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * AI 代理调度器（V13 / docs/12 §2.2）：每 5 分钟一次 tick。
 *
 * <p>是否真的产出由 {@link AgentRuntime} 决定（活跃时段 + 概率），这里只负责「按时叫醒」。
 * kill switch（{@code agent.enabled=false}）在 Runtime 内部判断，关闭时本次 tick 连查询都不做，
 * 因此开关切换后最多一个 tick（≤5 分钟）内生效，满足 docs/12 G5「5 秒内停止挑选」的语义
 * （tick 之间不会有新的挑选动作发生）。
 */
@Component
public class AgentScheduler {

    private static final Logger log = LoggerFactory.getLogger(AgentScheduler.class);

    private final AgentRuntime runtime;

    public AgentScheduler(AgentRuntime runtime) {
        this.runtime = runtime;
    }

    @Scheduled(cron = "${app.agent.tick-cron:0 */5 * * * *}", zone = "UTC")
    public void tick() {
        try {
            runtime.tick();
        } catch (RuntimeException ex) {
            // 调度器绝不能因为单个代理异常而停止
            log.error("AI 代理调度 tick 失败", ex);
        }
    }
}
