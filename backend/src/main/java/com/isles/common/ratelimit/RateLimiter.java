package com.isles.common.ratelimit;

import java.time.Duration;

/**
 * 限流计数器后端：固定窗口计数（docs/07 §7）。
 *
 * <p>实现必须线程安全；计数失败（如 Redis 不可用）应抛出运行时异常，
 * 由 {@link RateLimitService} 决定降级策略，禁止静默放过。
 */
public interface RateLimiter {

    /**
     * 在窗口内对 key 计数一次。
     *
     * @param key          计数键（已含策略与窗口桶）
     * @param window       窗口长度
     * @return 本次计数后的累计值（从 1 开始）
     */
    long increment(String key, Duration window);
}
