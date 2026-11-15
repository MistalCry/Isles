package com.isles.common.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 限流配置（application.yml 的 {@code app.rate-limit}）。
 *
 * @param enabled           总开关（本地联调可关闭，生产必须开启）
 * @param store             计数存储：{@code memory}（单机）/ {@code redis}（多实例）
 * @param trustForwardedFor 是否信任 {@code X-Forwarded-For}（部署在 Nginx 之后设为 true）
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(boolean enabled, String store, boolean trustForwardedFor) {

    public static RateLimitProperties defaults() {
        return new RateLimitProperties(true, "memory", false);
    }

    public boolean useRedis() {
        return "redis".equalsIgnoreCase(store);
    }
}
