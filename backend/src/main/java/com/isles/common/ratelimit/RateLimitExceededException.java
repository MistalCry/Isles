package com.isles.common.ratelimit;

import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;

/**
 * 触发限流（HTTP 429 + {@code Retry-After}）。
 *
 * <p>继承 {@link AppException} 以复用统一错误信封；{@code retryAfterSeconds} 由
 * {@code GlobalExceptionHandler} 写入响应头，前端可据此提示等待时长。
 */
public class RateLimitExceededException extends AppException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(RateLimitPolicy policy, long retryAfterSeconds) {
        super(ErrorCode.RATE_LIMITED,
                "操作过于频繁（%s：%d 次/%d 秒），请 %d 秒后重试"
                        .formatted(policy.name(), policy.limit(), policy.windowSeconds(),
                                Math.max(retryAfterSeconds, 1)));
        this.retryAfterSeconds = Math.max(retryAfterSeconds, 1);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
