package com.isles.common.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需要限流的接口方法（docs/07 §7）。
 *
 * <p>由 {@link RateLimitInterceptor} 读取；匿名接口按 IP 计数，登录接口按 userId 计数。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    RateLimitPolicy value();
}
