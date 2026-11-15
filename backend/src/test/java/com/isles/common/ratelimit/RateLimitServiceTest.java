package com.isles.common.ratelimit;

import com.isles.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 限流服务单元测试（docs/07 §7）：配额内放行、超限抛 429 并给出 Retry-After、总开关关闭时不计数。
 */
class RateLimitServiceTest {

    @SuppressWarnings("unchecked")
    private RateLimitService service(boolean enabled) {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return new RateLimitService(new RateLimitProperties(enabled, "memory", false), provider);
    }

    @Test
    void should_allow_requests_within_quota() {
        RateLimitService service = service(true);

        assertThatCode(() -> {
            for (int i = 0; i < RateLimitPolicy.LOGIN.limit(); i++) {
                service.check(RateLimitPolicy.LOGIN, "ip:127.0.0.1");
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void should_reject_request_over_quota_with_retry_after() {
        RateLimitService service = service(true);
        for (int i = 0; i < RateLimitPolicy.LOGIN.limit(); i++) {
            service.check(RateLimitPolicy.LOGIN, "ip:127.0.0.1");
        }

        assertThatThrownBy(() -> service.check(RateLimitPolicy.LOGIN, "ip:127.0.0.1"))
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(ex -> {
                    RateLimitExceededException rateLimited = (RateLimitExceededException) ex;
                    assertThat(rateLimited.getErrorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
                    assertThat(rateLimited.getRetryAfterSeconds()).isBetween(1L, 60L);
                });
    }

    @Test
    void should_not_count_when_disabled() {
        RateLimitService service = service(false);

        assertThatCode(() -> {
            for (int i = 0; i < RateLimitPolicy.LOGIN.limit() + 5; i++) {
                service.check(RateLimitPolicy.LOGIN, "ip:127.0.0.1");
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void should_isolate_subjects() {
        RateLimitService service = service(true);
        for (int i = 0; i < RateLimitPolicy.LOGIN.limit(); i++) {
            service.check(RateLimitPolicy.LOGIN, "ip:10.0.0.1");
        }

        assertThatCode(() -> service.check(RateLimitPolicy.LOGIN, "ip:10.0.0.2"))
                .doesNotThrowAnyException();
    }
}
