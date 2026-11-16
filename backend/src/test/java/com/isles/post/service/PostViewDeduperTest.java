package com.isles.post.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 浏览去重器单元测试：窗口内去重、窗口外重新计数、清理与参数校验。
 */
class PostViewDeduperTest {

    private static final long MINUTE = 60_000L;

    @Test
    void should_count_first_view_and_skip_repeat_within_window() {
        PostViewDeduper deduper = new PostViewDeduper(Duration.ofMinutes(30));
        long now = 1_000_000L;

        assertThat(deduper.shouldCount("p1|u:u1", now)).isTrue();
        assertThat(deduper.shouldCount("p1|u:u1", now + 29 * MINUTE)).isFalse();
        // 不同用户/不同帖子互不影响
        assertThat(deduper.shouldCount("p1|u:u2", now)).isTrue();
        assertThat(deduper.shouldCount("p2|u:u1", now)).isTrue();
    }

    @Test
    void should_count_again_after_window() {
        PostViewDeduper deduper = new PostViewDeduper(Duration.ofMinutes(30));
        long now = 1_000_000L;

        assertThat(deduper.shouldCount("p1|u:u1", now)).isTrue();
        assertThat(deduper.shouldCount("p1|u:u1", now + 30 * MINUTE)).isTrue();
        assertThat(deduper.shouldCount("p1|u:u1", now + 59 * MINUTE)).isFalse();
    }

    @Test
    void should_treat_null_key_as_always_counted() {
        PostViewDeduper deduper = new PostViewDeduper(Duration.ofMinutes(30));

        // 拿不到会话标识（非 Web 线程）时不做去重，避免误吞浏览量
        assertThat(deduper.shouldCount(null, 1L)).isTrue();
        assertThat(deduper.shouldCount("  ", 1L)).isTrue();
        assertThat(deduper.size()).isZero();
    }

    @Test
    void should_purge_expired_entries_when_growing() {
        PostViewDeduper deduper = new PostViewDeduper(Duration.ofMinutes(1));
        long now = 1_000_000L;
        for (int i = 0; i < PostViewDeduper.PURGE_THRESHOLD + 10; i++) {
            deduper.shouldCount("p" + i + "|u:u1", now);
        }
        assertThat(deduper.size()).isGreaterThan(0);

        // 一分钟后再次写入：过期键应被清理，容量回落到阈值以内
        deduper.shouldCount("p-new|u:u1", now + 2 * MINUTE);
        assertThat(deduper.size()).isLessThan(PostViewDeduper.PURGE_THRESHOLD);
    }

    @Test
    void should_reject_non_positive_window() {
        assertThatThrownBy(() -> new PostViewDeduper(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PostViewDeduper(Duration.ofMinutes(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
