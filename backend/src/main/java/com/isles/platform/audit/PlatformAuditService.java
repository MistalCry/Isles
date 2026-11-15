package com.isles.platform.audit;

import com.isles.common.id.UlidGenerator;
import com.isles.common.json.JsonSupport;
import com.isles.common.pagination.OffsetPage;
import com.isles.common.security.CurrentUserProvider;
import com.isles.common.time.ClockTimes;
import com.isles.user.entity.User;
import com.isles.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 平台操作审计服务（docs/00 §3.2「敏感操作写审计日志」、docs/11 §7.2）。
 *
 * <p>写入失败不阻断主流程：管理动作已生效优先，记录 WARN 便于排查（与频道级
 * {@code AuditLogService} 的策略保持一致）。查询只读，不提供修改与删除。
 */
@Service
@RequiredArgsConstructor
public class PlatformAuditService {

    private static final Logger log = LoggerFactory.getLogger(PlatformAuditService.class);
    private static final int MAX_PAGE_SIZE = 100;

    private final PlatformAuditLogMapper auditLogMapper;
    private final CurrentUserProvider currentUserProvider;
    private final UserMapper userMapper;
    private final JsonSupport jsonSupport;

    /**
     * 记录审计：操作人取自当前登录上下文（后台接口必然已登录）。
     */
    public void record(String action, String targetType, String targetId, Map<String, Object> detail) {
        String actorId = currentUserProvider.currentUserIdOrNull();
        record(actorId, resolveActorName(actorId), action, targetType, targetId, detail);
    }

    /**
     * 记录审计：调用方已持有操作人信息时使用，避免重复查库。
     */
    public void record(String actorId, String actorName, String action, String targetType, String targetId,
                       Map<String, Object> detail) {
        if (action == null || action.isBlank()) {
            return;
        }
        try {
            PlatformAuditLog entry = new PlatformAuditLog();
            entry.setId(UlidGenerator.generate());
            entry.setActorId(actorId);
            entry.setActorName(actorName);
            entry.setAction(action);
            entry.setTargetType(targetType);
            entry.setTargetId(targetId);
            entry.setDetail(detail == null || detail.isEmpty() ? null : jsonSupport.toJson(detail));
            entry.setIp(resolveClientIp());
            entry.setUserAgent(resolveUserAgent());
            entry.setCreatedAt(ClockTimes.nowUtc());
            auditLogMapper.insert(entry);
        } catch (RuntimeException ex) {
            log.warn("平台审计写入失败 action={} target={}/{}", action, targetType, targetId, ex);
        }
    }

    /**
     * 审计列表（后台偏移分页）。
     */
    public OffsetPage<PlatformAuditLogView> list(String actorId, String action, String targetType, String targetId,
                                                 LocalDateTime from, LocalDateTime to, Integer page, Integer size) {
        int pageNo = page == null || page <= 0 ? 1 : page;
        int pageSize = size == null || size <= 0 ? 20 : Math.min(size, MAX_PAGE_SIZE);
        String actorFilter = blankToNull(actorId);
        String actionFilter = blankToNull(action);
        String targetTypeFilter = blankToNull(targetType);
        String targetIdFilter = blankToNull(targetId);

        long total = auditLogMapper.countPage(actorFilter, actionFilter, targetTypeFilter, targetIdFilter, from, to);
        List<PlatformAuditLog> rows = auditLogMapper.selectPage(actorFilter, actionFilter, targetTypeFilter,
                targetIdFilter, from, to, pageSize, (pageNo - 1) * pageSize);
        List<PlatformAuditLogView> items = rows.stream()
                .map(row -> new PlatformAuditLogView(row.getId(), row.getActorId(), row.getActorName(),
                        row.getAction(), row.getTargetType(), row.getTargetId(), row.getDetail(), row.getIp(),
                        row.getUserAgent(), ClockTimes.toInstant(row.getCreatedAt())))
                .toList();
        return new OffsetPage<>(items, total);
    }

    private String resolveActorName(String actorId) {
        if (actorId == null) {
            return null;
        }
        User user = userMapper.selectById(actorId);
        if (user == null) {
            return null;
        }
        return user.getNickname() == null ? user.getUsername() : user.getNickname();
    }

    private String resolveClientIp() {
        ServletRequestAttributes attributes = currentRequest();
        return attributes == null ? null : attributes.getRequest().getRemoteAddr();
    }

    private String resolveUserAgent() {
        ServletRequestAttributes attributes = currentRequest();
        if (attributes == null) {
            return null;
        }
        String userAgent = attributes.getRequest().getHeader("User-Agent");
        if (userAgent == null) {
            return null;
        }
        return userAgent.length() > 255 ? userAgent.substring(0, 255) : userAgent;
    }

    private ServletRequestAttributes currentRequest() {
        try {
            RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
            return attributes instanceof ServletRequestAttributes servletAttributes ? servletAttributes : null;
        } catch (RuntimeException ex) {
            // 非 Web 线程（定时任务/单测）：无请求上下文
            return null;
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
