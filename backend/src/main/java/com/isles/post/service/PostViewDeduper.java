package com.isles.post.service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 浏览去重器：同一「会话」在时间窗内对同一帖子的重复访问只计一次。
 *
 * <p>为什么需要：帖子详情每次读取都 {@code view_count + 1}，作者刷新、F5、爬虫会把热度公式污染成
 * 「谁点得多谁火」。这里用进程内 Map 做 30 分钟去重，实现简单、无额外依赖。
 *
 * <p><b>已知限制</b>：内存态，多实例部署时各实例独立计数（去重率下降但不影响正确性）；
 * 进程重启后窗口丢失（重启后首次访问会再计一次）。单实例部署（docs/02 §1.2）下行为符合预期。
 *
 * <p>线程安全：{@link ConcurrentHashMap} + 无锁读；并发首次访问可能各计一次（可接受，不引入锁开销）。
 * 条目数超过 {@link #PURGE_THRESHOLD} 时顺带清理过期键，避免长期运行内存增长。
 */
public class PostViewDeduper {

    /** 清理阈值：超过后触发一次过期键清理。 */
    static final int PURGE_THRESHOLD = 10_000;

    private final long windowMillis;
    private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();

    public PostViewDeduper(Duration window) {
        if (window == null || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("去重窗口必须为正数");
        }
        this.windowMillis = window.toMillis();
    }

    /**
     * 是否应该计一次浏览。
     *
     * @param key       去重键（帖子 + 会话）
     * @param nowMillis 当前时间（毫秒），由调用方传入便于测试
     * @return true=首次或在窗口外，需要计数；false=窗口内重复访问
     */
    public boolean shouldCount(String key, long nowMillis) {
        if (key == null || key.isBlank()) {
            return true;
        }
        Long previous = lastSeen.get(key);
        if (previous != null && nowMillis - previous < windowMillis) {
            return false;
        }
        lastSeen.put(key, nowMillis);
        if (lastSeen.size() > PURGE_THRESHOLD) {
            purge(nowMillis);
        }
        return true;
    }

    /** 当前记录的键数量（测试与运维观测用）。 */
    public int size() {
        return lastSeen.size();
    }

    /** 清空（测试用）。 */
    public void clear() {
        lastSeen.clear();
    }

    private void purge(long nowMillis) {
        lastSeen.entrySet().removeIf(entry -> nowMillis - entry.getValue() >= windowMillis);
    }
}
