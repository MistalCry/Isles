package com.isles.common.time;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 时间工具：数据库统一存 UTC（docs/00 §3.1），API 输出 ISO 8601 UTC。
 *
 * <p>实体使用 {@link LocalDateTime}（MySQL DATETIME 无时区），DTO 使用 {@link Instant}（序列化为带 Z 的 ISO 串）。
 */
public final class ClockTimes {

    private ClockTimes() {
    }

    public static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    public static Instant toInstant(LocalDateTime utcDateTime) {
        return utcDateTime == null ? null : utcDateTime.toInstant(ZoneOffset.UTC);
    }

    public static LocalDateTime fromInstant(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
