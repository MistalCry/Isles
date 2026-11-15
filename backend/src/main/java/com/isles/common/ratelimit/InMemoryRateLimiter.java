package com.isles.common.ratelimit;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内固定窗口限流（单机默认实现，docs/07 §7「M1 可用本地缓存」）。
 *
 * <p>计数键包含窗口桶，过期条目在下一次访问时惰性清理；条目数超过阈值时做一次全量清理，
 * 避免长期运行内存膨胀。多实例部署时必须切换 Redis 实现，否则限额按实例放大。
 */
@Component
public class InMemoryRateLimiter implements RateLimiter {

    private static final int CLEANUP_THRESHOLD = 10_000;

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Map<String, Long> expireAt = new ConcurrentHashMap<>();

    @Override
    public long increment(String key, Duration window) {
        long now = System.currentTimeMillis();
        long expires = now + window.toMillis();
        if (counters.size() > CLEANUP_THRESHOLD) {
            cleanup(now);
        }
        AtomicLong counter = counters.computeIfAbsent(key, ignored -> {
            expireAt.put(key, expires);
            return new AtomicLong();
        });
        return counter.incrementAndGet();
    }

    private void cleanup(long now) {
        expireAt.entrySet().removeIf(entry -> {
            if (entry.getValue() <= now) {
                counters.remove(entry.getKey());
                return true;
            }
            return false;
        });
    }

    /** 单测用：清空计数。 */
    public void clear() {
        counters.clear();
        expireAt.clear();
    }
}
