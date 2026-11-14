package com.isles.common.security;

import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 当前登录用户上下文：从 Spring Security 的 {@link SecurityContextHolder} 读取用户 ID。
 *
 * <p>JWT 过滤器认证成功后 principal 为 userId 字符串。游客请求返回 null（{@link #currentUserIdOrNull()}）
 * 或由 {@link #requireUserId()} 抛 401。
 */
@Component
public class CurrentUserProvider {

    /**
     * 未登录返回 null，不抛异常（用于公开接口的可选个性化）。
     */
    public String currentUserIdOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (authentication instanceof UsernamePasswordAuthenticationToken && principal instanceof String userId
                && !userId.isBlank() && !"anonymousUser".equals(userId)) {
            return userId;
        }
        return null;
    }

    /**
     * 必须登录的接口调用；游客抛 401。
     */
    public String requireUserId() {
        String userId = currentUserIdOrNull();
        if (userId == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }
}
