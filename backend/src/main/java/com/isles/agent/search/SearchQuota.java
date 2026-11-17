package com.isles.agent.search;

import com.isles.platform.setting.PlatformSettingService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 联网搜索日配额（{@code agent.search.dailyQuota}，默认 200）。
 *
 * <p>计数放在**进程内**：平台当前单实例部署，配额是成本护栏而非计费依据；重启归零属可接受误差
 * （已在 docs/07 §5.2 标注为已知限制）。若将来多实例，需要换成 Redis 或落库计数。
 *
 * <p>{@code dailyQuota=0} 表示**不限**（关闭联网搜索请用 {@code agent.search.provider=NONE}）。
 */
@Component
@RequiredArgsConstructor
public class SearchQuota {

    private static final Logger log = LoggerFactory.getLogger(SearchQuota.class);

    private final PlatformSettingService settingService;

    private final AtomicInteger used = new AtomicInteger();
    private volatile LocalDate day = LocalDate.now(ZoneOffset.UTC);

    /**
     * 尝试消耗一次配额。
     *
     * @return true=允许本次搜索；false=今日配额已用尽
     */
    public boolean tryConsume() {
        rolloverIfNeeded();
        int limit = limit();
        if (limit <= 0) {
            used.incrementAndGet();
            return true;
        }
        while (true) {
            int current = used.get();
            if (current >= limit) {
                return false;
            }
            if (used.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /**
     * 退还一次配额（第 21 轮）：上游请求失败或 0 结果时调用，保证「只有真正拿到结果才计数」。
     *
     * <p>不会退到负数：跨日归零后再退，`used` 已是 0，`max(0)` 兜住。
     */
    public void refund() {
        rolloverIfNeeded();
        used.updateAndGet(current -> Math.max(current - 1, 0));
    }

    public int used() {
        rolloverIfNeeded();
        return used.get();
    }

    public int limit() {
        int value = settingService.getInt("agent.search.dailyQuota");
        return Math.max(value, 0);
    }

    /**
     * 跨 UTC 日归零（与库内 DATETIME(3) 的日界一致）。
     */
    private void rolloverIfNeeded() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (!today.equals(day)) {
            synchronized (this) {
                if (!today.equals(day)) {
                    log.info("联网搜索配额跨日归零 date={} used={}", today, used.get());
                    used.set(0);
                    day = today;
                }
            }
        }
    }
}
