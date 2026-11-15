package com.isles.platform.permission;

import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.security.CurrentUserProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 平台权限拦截器（docs/11 §7.2）。
 *
 * <p>规则：
 * <ol>
 *   <li>方法或类上有 {@link RequirePlatformPermission} → 校验对应权限点；</li>
 *   <li>未标注但路径属于 {@code /api/v1/admin/**} → 至少要求任意平台角色（兜底，避免漏标即越权）；</li>
 *   <li>其余接口直接放行。</li>
 * </ol>
 * 校验失败抛 {@link AppException}，由全局异常处理器输出 403 错误信封。
 */
@Component
@RequiredArgsConstructor
public class PlatformPermissionInterceptor implements HandlerInterceptor {

    private static final String ADMIN_PATH_PREFIX = "/api/v1/admin";

    private final PlatformAdminService adminService;
    private final CurrentUserProvider currentUserProvider;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequirePlatformPermission annotation = handlerMethod.getMethodAnnotation(RequirePlatformPermission.class);
        if (annotation == null) {
            annotation = handlerMethod.getBeanType().getAnnotation(RequirePlatformPermission.class);
        }

        String userId = currentUserProvider.requireUserId();
        if (annotation != null) {
            if (!adminService.hasPermission(userId, annotation.value())) {
                throw new AppException(ErrorCode.FORBIDDEN,
                        "需要平台权限：" + annotation.value().label());
            }
            return true;
        }

        String uri = request.getRequestURI();
        if (uri != null && uri.startsWith(ADMIN_PATH_PREFIX) && adminService.resolveRole(userId) == null) {
            throw new AppException(ErrorCode.FORBIDDEN, "需要平台管理员权限");
        }
        return true;
    }
}
