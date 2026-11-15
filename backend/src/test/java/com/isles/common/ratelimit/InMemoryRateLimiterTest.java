package com.isles.common.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 进程内限流计数器单元测试：同一键累计、不同键隔离、窗口过期后重新计数。
 */
class InMemoryRateLimiterTest {

    private final InMemoryRateLimiter limiter = new InMemoryRateLimiter();

    @Test
    void should_accumulate_counts_for_same_key() {
        Duration window = Duration.ofMinutes(1);

        assertThat(limiter.increment("k1", window)).isEqualTo(1);
        assertThat(limiter.increment("k1", window)).isEqualTo(2);
        assertThat(limiter.increment("k1", window)).isEqualTo(3);
    }

    @Test
    void should_isolate_different_keys() {
        Duration window = Duration.ofMinutes(1);

        limiter.increment("a", window);
        limiter.increment("a", window);

        assertThat(limiter.increment("b", window)).isEqualTo(1);
    }

    @Test
    void should_reset_after_clear() {
        Duration window = Duration.ofMinutes(1);
        limiter.increment("k1", window);

        limiter.clear();

        assertThat(limiter.increment("k1", window)).isEqualTo(1);
    }
}
