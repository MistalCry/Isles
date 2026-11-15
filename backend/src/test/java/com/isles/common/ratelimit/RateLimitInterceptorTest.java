package com.isles.common.ratelimit;

import com.isles.common.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 限流拦截器单元测试：注解识别、游客按 IP、登录用户按 userId、OPTIONS 预检放行。
 */
class RateLimitInterceptorTest {

    private final RateLimitService rateLimitService = mock(RateLimitService.class);
    private final CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
    private final RateLimitInterceptor interceptor = new RateLimitInterceptor(rateLimitService,
            new RateLimitProperties(true, "memory", false), currentUserProvider);

    @Test
    void should_use_client_ip_for_anonymous_request() throws Exception {
        when(rateLimitService.enabled()).thenReturn(true);
        when(currentUserProvider.currentUserIdOrNull()).thenReturn(null);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("203.0.113.7");

        boolean allowed = interceptor.preHandle(request, new MockHttpServletResponse(), handler("login"));

        verify(rateLimitService).check(RateLimitPolicy.LOGIN, "ip:203.0.113.7");
        assertThat(allowed).isTrue();
    }

    @Test
    void should_use_user_id_for_authenticated_request() throws Exception {
        when(rateLimitService.enabled()).thenReturn(true);
        when(currentUserProvider.currentUserIdOrNull()).thenReturn("user-1");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/posts");

        boolean allowed = interceptor.preHandle(request, new MockHttpServletResponse(), handler("createPost"));

        verify(rateLimitService).check(RateLimitPolicy.POST_CREATE, "u:user-1");
        assertThat(allowed).isTrue();
    }

    @Test
    void should_trust_forwarded_for_when_configured() throws Exception {
        RateLimitInterceptor trusting = new RateLimitInterceptor(rateLimitService,
                new RateLimitProperties(true, "memory", true), currentUserProvider);
        when(rateLimitService.enabled()).thenReturn(true);
        when(currentUserProvider.currentUserIdOrNull()).thenReturn(null);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "198.51.100.9, 10.0.0.1");

        trusting.preHandle(request, new MockHttpServletResponse(), handler("login"));

        verify(rateLimitService).check(RateLimitPolicy.LOGIN, "ip:198.51.100.9");
    }

    @Test
    void should_skip_options_preflight() throws Exception {
        when(rateLimitService.enabled()).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/v1/auth/login");

        boolean allowed = interceptor.preHandle(request, new MockHttpServletResponse(), handler("login"));

        verify(rateLimitService, never()).check(eq(RateLimitPolicy.LOGIN), org.mockito.ArgumentMatchers.anyString());
        assertThat(allowed).isTrue();
    }

    private HandlerMethod handler(String methodName) throws NoSuchMethodException {
        TestHandlers target = new TestHandlers();
        return new HandlerMethod(target, TestHandlers.class.getMethod(methodName));
    }

    /** 承载注解的测试目标（方法签名无需真实业务逻辑）。 */
    static class TestHandlers {

        @RateLimit(RateLimitPolicy.LOGIN)
        public void login() {
        }

        @RateLimit(RateLimitPolicy.POST_CREATE)
        public void createPost() {
        }
    }
}
