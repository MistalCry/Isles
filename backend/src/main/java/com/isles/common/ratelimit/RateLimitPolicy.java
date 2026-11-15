package com.isles.common.ratelimit;

/**
 * 限流策略（docs/07 §7）：按接口维度的固定窗口配额。
 *
 * <p>策略是代码常量而非配置项，避免运维误调导致防滥用失效；仅通过 {@code app.rate-limit.enabled}
 * 与存储后端（内存/Redis）做环境差异。
 */
public enum RateLimitPolicy {

    /** 登录：每 IP 10 次/分钟（docs/07 §7）。 */
    LOGIN(10, 60),

    /** 注册：每 IP 5 次/小时。 */
    REGISTER(5, 3600),

    /** 发帖：每用户 10 次/分钟（频道级 60 次/小时在服务层另有配额）。 */
    POST_CREATE(10, 60),

    /** 评论：每用户 30 次/分钟。 */
    COMMENT_CREATE(30, 60),

    /** 上传：每用户 30 次/小时。 */
    UPLOAD(30, 3600),

    /** 搜索：每用户/IP 60 次/分钟。 */
    SEARCH(60, 60);

    private final int limit;
    private final int windowSeconds;

    RateLimitPolicy(int limit, int windowSeconds) {
        this.limit = limit;
        this.windowSeconds = windowSeconds;
    }

    public int limit() {
        return limit;
    }

    public int windowSeconds() {
        return windowSeconds;
    }
}
