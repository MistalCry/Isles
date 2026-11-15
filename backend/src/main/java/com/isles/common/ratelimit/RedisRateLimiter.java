package com.isles.common.ratelimit;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * Redis 固定窗口限流（多实例部署使用，docs/07 §7）。
 *
 * <p>使用 {@code INCR} + 首次写入设置 TTL，保证多实例共享同一配额。
 * Redis 不可用时抛出运行时异常，由 {@link RateLimitService} 降级到进程内计数，
 * 避免因缓存故障导致全站写操作被拒。
 */
public class RedisRateLimiter implements RateLimiter {

    private final StringRedisTemplate redisTemplate;

    public RedisRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public long increment(String key, Duration window) {
        Long value = redisTemplate.opsForValue().increment(key);
        if (value == null) {
            throw new IllegalStateException("Redis INCR 返回空值: " + key);
        }
        if (value == 1L) {
            redisTemplate.expire(key, window);
        }
        return value;
    }
}
