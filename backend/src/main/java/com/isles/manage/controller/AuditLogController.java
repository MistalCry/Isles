package com.isles.manage.controller;

import com.isles.channel.audit.AuditLogResponse;
import com.isles.channel.audit.AuditLogService;
import com.isles.channel.service.ChannelAccessService;
import com.isles.channel.service.ChannelPermissions;
import com.isles.common.pagination.OffsetPage;
import com.isles.common.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 管理日志查询（FR-MGR-07）：按操作人/类型/时间筛选（channel.log.view）。
 */
@RestController
@RequestMapping("/api/v1/channels")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService auditLogService;
    private final ChannelAccessService accessService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping("/{id}/audit-logs")
    public OffsetPage<AuditLogResponse> list(
            @PathVariable String id,
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "20") Integer size) {
        String operatorId = currentUserProvider.requireUserId();
        accessService.requirePermission(id, operatorId, ChannelPermissions.LOG_VIEW);
        return auditLogService.listOffset(id, actorId, action, targetType,
                from == null ? null : from.atStartOfDay(),
                to == null ? null : to.plusDays(1).atStartOfDay(),
                page, size);
    }
}
