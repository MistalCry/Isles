package com.isles.platform.penalty;

import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.security.CurrentUserProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.regex.Pattern;

/**
 * 全站禁言拦截器（docs/11 §6.3）：命中「发布/编辑内容」的写接口时校验生效中的 MUTE 处罚。
 *
 * <p>只拦截内容生产接口（发帖、编辑帖子、评论），点赞、分享、关注等互动不受禁言影响。
 * 频道内禁言仍由 {@code ChannelAccessService} 负责，两者互不替代。
 */
@Component
@RequiredArgsConstructor
public class PlatformMuteInterceptor implements HandlerInterceptor {

    private static final Pattern CREATE_POST = Pattern.compile("^/api/v1/channels/[^/]+/posts$");
    private static final Pattern EDIT_POST = Pattern.compile("^/api/v1/posts/[^/]+$");
    private static final Pattern CREATE_COMMENT = Pattern.compile("^/api/v1/posts/[^/]+/comments$");

    private final PenaltyService penaltyService;
    private final CurrentUserProvider currentUserProvider;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!isContentWrite(request)) {
            return true;
        }
        String userId = currentUserProvider.currentUserIdOrNull();
        if (userId == null) {
            // 未登录交给安全链返回 401
            return true;
        }
        if (penaltyService.isMuted(userId)) {
            throw new AppException(ErrorCode.CHANNEL_MUTED, "你已被平台禁言，暂时无法发布内容");
        }
        return true;
    }

    private boolean isContentWrite(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return false;
        }
        String method = request.getMethod();
        if ("POST".equalsIgnoreCase(method)) {
            return CREATE_POST.matcher(uri).matches() || CREATE_COMMENT.matcher(uri).matches();
        }
        if ("PATCH".equalsIgnoreCase(method)) {
            return EDIT_POST.matcher(uri).matches();
        }
        return false;
    }
}
