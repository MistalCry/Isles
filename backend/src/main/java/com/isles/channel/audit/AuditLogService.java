package com.isles.channel.audit;

import com.isles.channel.mapper.ChannelAuditLogMapper;
import com.isles.common.id.UlidGenerator;
import com.isles.common.json.JsonSupport;
import com.isles.common.pagination.CursorUtils;
import com.isles.common.pagination.OffsetPage;
import com.isles.common.pagination.PageResult;
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
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 管理日志服务（docs/03 §5.2 AuditLogService）：频道内写操作与关键管理操作统一记录。
 *
 * <p>写入失败不应阻断主流程（管理操作已成功优先）：记录 WARN 日志后继续。
 * 查询仅频道管理日志权限（channel.log.view）可见，由 Controller 校验。
 */
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final ChannelAuditLogMapper auditLogMapper;
    private final UserMapper userMapper;
    private final JsonSupport jsonSupport;

    /**
     * 记录一条管理日志；detail 为可序列化 Map（变更前后、原因等）。
     */
    public void record(String channelId, String actorId, String action, String targetType, String targetId,
                       Map<String, Object> detail) {
        if (channelId == null || actorId == null || action == null) {
            return;
        }
        try {
            ChannelAuditLog entry = new ChannelAuditLog();
            entry.setId(UlidGenerator.generate());
            entry.setChannelId(channelId);
            entry.setActorId(actorId);
            entry.setAction(action);
            entry.setTargetType(targetType);
            entry.setTargetId(targetId);
            entry.setDetail(detail == null || detail.isEmpty() ? null : jsonSupport.toJson(detail));
            entry.setIp(resolveClientIp());
            entry.setCreatedAt(ClockTimes.nowUtc());
            auditLogMapper.insert(entry);
        } catch (RuntimeException ex) {
            // 审计失败不影响主业务；记录 WARN 便于排查
            log.warn("管理日志写入失败 channelId={} action={} actorId={}", channelId, action, actorId, ex);
        }
    }

    public void record(String channelId, String actorId, String action, String targetType, String targetId) {
        record(channelId, actorId, action, targetType, targetId, null);
    }

    /**
     * 日志查询：按操作者/动作/目标类型/时间筛选，ULID 游标分页。
     */
    public PageResult<AuditLogResponse> list(String channelId, String actorId, String action, String targetType,
                                             LocalDateTime from, LocalDateTime to, String cursor, Integer limit) {
        int pageSize = CursorUtils.normalizeLimit(limit);
        String cursorId = CursorUtils.validateIdCursor(cursor);
        List<ChannelAuditLog> rows = auditLogMapper.selectPage(channelId, actorId, action, targetType, from, to,
                cursorId, pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<ChannelAuditLog> page = hasMore ? rows.subList(0, pageSize) : rows;

        List<String> actorIds = page.stream().map(ChannelAuditLog::getActorId).distinct().toList();
        Map<String, User> actors = actorIds.isEmpty()
                ? Map.of()
                : userMapper.selectBatchIds(actorIds).stream()
                        .collect(Collectors.toMap(User::getId, Function.identity()));

        List<AuditLogResponse> items = page.stream()
                .map(row -> new AuditLogResponse(
                        row.getId(),
                        row.getChannelId(),
                        row.getActorId(),
                        actors.get(row.getActorId()) == null ? null : actors.get(row.getActorId()).getNickname(),
                        row.getAction(),
                        row.getTargetType(),
                        row.getTargetId(),
                        row.getDetail(),
                        row.getIp(),
                        ClockTimes.toInstant(row.getCreatedAt())))
                .toList();
        String nextCursor = hasMore && !items.isEmpty() ? items.get(items.size() - 1).id() : null;
        return new PageResult<>(items, nextCursor);
    }

    /**
     * 管理端偏移分页查询（page/size，返回 total）。
     */
    public OffsetPage<AuditLogResponse> listOffset(String channelId, String actorId, String action,
                                                   String targetType, LocalDateTime from, LocalDateTime to,
                                                   int page, int size) {
        int pageNumber = Math.max(1, page);
        int pageSize = Math.min(Math.max(1, size), 100);
        long total = auditLogMapper.countPage(channelId, actorId, action, targetType, from, to);
        List<ChannelAuditLog> rows = auditLogMapper.selectPageOffset(channelId, actorId, action, targetType,
                from, to, (pageNumber - 1) * pageSize, pageSize);
        List<String> actorIds = rows.stream().map(ChannelAuditLog::getActorId).distinct().toList();
        Map<String, User> actors = actorIds.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(actorIds).stream()
                        .collect(Collectors.toMap(User::getId, Function.identity()));
        List<AuditLogResponse> items = rows.stream()
                .map(row -> new AuditLogResponse(row.getId(), row.getChannelId(), row.getActorId(),
                        actors.get(row.getActorId()) == null ? null : actors.get(row.getActorId()).getNickname(),
                        row.getAction(), row.getTargetType(), row.getTargetId(), row.getDetail(), row.getIp(),
                        ClockTimes.toInstant(row.getCreatedAt())))
                .toList();
        return new OffsetPage<>(items, total);
    }

    /**
     * 从当前 HTTP 请求解析客户端 IP；非 Web 线程（定时任务/单测）返回 null。
     */
    private String resolveClientIp() {
        try {
            RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
            if (attributes instanceof ServletRequestAttributes servletRequestAttributes) {
                return servletRequestAttributes.getRequest().getRemoteAddr();
            }
        } catch (RuntimeException ex) {
            // 忽略：非请求上下文
        }
        return null;
    }
}
