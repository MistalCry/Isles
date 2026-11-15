package com.isles.common.ratelimit;

import com.isles.common.security.CurrentUserProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 限流拦截器：读取 {@link RateLimit} 注解并对当前请求计数。
 *
 * <p>主体判定：已登录按 userId 计数（跨设备共享配额），游客按客户端 IP 计数。
 * 拦截器只做「计数 + 超限拒绝」，业务语义仍在服务层。
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimitService rateLimitService;
    private final RateLimitProperties properties;
    private final CurrentUserProvider currentUserProvider;

    public RateLimitInterceptor(RateLimitService rateLimitService, RateLimitProperties properties,
                                CurrentUserProvider currentUserProvider) {
        this.rateLimitService = rateLimitService;
        this.properties = properties;
        this.currentUserProvider = currentUserProvider;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!rateLimitService.enabled() || !(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        RateLimit annotation = handlerMethod.getMethodAnnotation(RateLimit.class);
        if (annotation == null) {
            annotation = handlerMethod.getBeanType().getAnnotation(RateLimit.class);
        }
        if (annotation == null) {
            return true;
        }
        rateLimitService.check(annotation.value(), subject(request));
        return true;
    }

    private String subject(HttpServletRequest request) {
        String userId = currentUserProvider.currentUserIdOrNull();
        if (userId != null) {
            return "u:" + userId;
        }
        return "ip:" + clientIp(request);
    }

    private String clientIp(HttpServletRequest request) {
        if (properties.trustForwardedFor()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
            String realIp = request.getHeader("X-Real-IP");
            if (realIp != null && !realIp.isBlank()) {
                return realIp.trim();
            }
        }
        return request.getRemoteAddr();
    }
}
