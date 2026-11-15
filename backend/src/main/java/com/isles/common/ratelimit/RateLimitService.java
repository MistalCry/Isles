package com.isles.common.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 限流服务：按策略 + 主体计数，超限抛 {@link RateLimitExceededException}（docs/07 §7）。
 *
 * <p>存储选择：{@code app.rate-limit.store=redis} 时优先 Redis；Redis 调用异常自动降级到
 * 进程内计数并打 WARN（缓存故障不应阻断业务写操作）。
 */
@Component
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    private final RateLimitProperties properties;
    private final InMemoryRateLimiter memoryLimiter;
    private final RedisRateLimiter redisLimiter;

    public RateLimitService(RateLimitProperties properties,
                            ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this.properties = properties;
        this.memoryLimiter = new InMemoryRateLimiter();
        StringRedisTemplate template = properties.useRedis() ? redisTemplateProvider.getIfAvailable() : null;
        this.redisLimiter = template == null ? null : new RedisRateLimiter(template);
        if (properties.useRedis() && template == null) {
            log.warn("限流配置为 redis，但未找到 StringRedisTemplate，降级为进程内限流");
        }
    }

    public boolean enabled() {
        return properties.enabled();
    }

    /**
     * 计数一次，超限抛 429。
     *
     * @param policy  限流策略
     * @param subject 主体标识（{@code u:<userId>} 或 {@code ip:<addr>}）
     */
    public void check(RateLimitPolicy policy, String subject) {
        if (!properties.enabled()) {
            return;
        }
        long nowSeconds = System.currentTimeMillis() / 1000;
        int window = policy.windowSeconds();
        long bucket = nowSeconds / window;
        String key = "rl:%s:%s:%d".formatted(policy.name(), subject, bucket);
        long count = increment(key, window);
        if (count > policy.limit()) {
            long retryAfter = window - (nowSeconds % window);
            log.info("触发限流 policy={} subject={} count={} limit={}", policy.name(), subject, count,
                    policy.limit());
            throw new RateLimitExceededException(policy, retryAfter);
        }
    }

    private long increment(String key, int windowSeconds) {
        if (redisLimiter != null) {
            try {
                return redisLimiter.increment(key, java.time.Duration.ofSeconds(windowSeconds));
            } catch (RuntimeException ex) {
                log.warn("Redis 限流计数失败，降级为进程内计数 key={} error={}", key, ex.getMessage());
            }
        }
        return memoryLimiter.increment(key, java.time.Duration.ofSeconds(windowSeconds));
    }
}
