package com.isles.common.web;

import com.isles.common.id.UlidGenerator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * 请求追踪过滤器：为每个请求生成/透传 requestId，写入响应头 {@code X-Request-Id} 与日志 MDC。
 *
 * <p>优先级最高，确保即使请求被安全过滤器链拒绝（401/403），响应仍带有 requestId 与错误体。
 * 客户端可通过请求头传入合法 requestId 用于链路透传，非法值一律忽略并重新生成。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String ATTRIBUTE = "requestId";
    public static final String MDC_KEY = "requestId";

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = request.getHeader(HEADER);
        if (requestId == null || !SAFE_ID.matcher(requestId).matches()) {
            requestId = "req_" + UlidGenerator.generate();
        }
        request.setAttribute(ATTRIBUTE, requestId);
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * 解析当前请求的 requestId：优先请求属性，其次 MDC，最后兜底生成。
     */
    public static String resolve(HttpServletRequest request) {
        if (request != null) {
            Object value = request.getAttribute(ATTRIBUTE);
            if (value instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        String fromMdc = MDC.get(MDC_KEY);
        if (fromMdc != null && !fromMdc.isBlank()) {
            return fromMdc;
        }
        return "req_" + UlidGenerator.generate();
    }
}
