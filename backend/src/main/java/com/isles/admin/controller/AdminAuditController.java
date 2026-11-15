package com.isles.admin.controller;

import com.isles.common.pagination.OffsetPage;
import com.isles.common.time.ClockTimes;
import com.isles.platform.audit.PlatformAuditLogView;
import com.isles.platform.audit.PlatformAuditService;
import com.isles.platform.permission.PlatformPermission;
import com.isles.platform.permission.RequirePlatformPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 平台审计查询接口（docs/11 §5.3）。
 *
 * <p>只读：审计表不提供更新与删除入口（docs/00 §3.2 敏感操作留痕）。
 */
@RestController
@RequestMapping("/api/v1/admin/audit-logs")
@RequiredArgsConstructor
public class AdminAuditController {

    private final PlatformAuditService auditService;

    @GetMapping
    @RequirePlatformPermission(PlatformPermission.AUDIT_READ)
    public OffsetPage<PlatformAuditLogView> list(@RequestParam(required = false) String actorId,
                                                 @RequestParam(required = false) String action,
                                                 @RequestParam(required = false) String targetType,
                                                 @RequestParam(required = false) String targetId,
                                                 @RequestParam(required = false) Instant from,
                                                 @RequestParam(required = false) Instant to,
                                                 @RequestParam(required = false) Integer page,
                                                 @RequestParam(required = false) Integer size) {
        return auditService.list(actorId, action, targetType, targetId,
                ClockTimes.fromInstant(from), ClockTimes.fromInstant(to), page, size);
    }
}
