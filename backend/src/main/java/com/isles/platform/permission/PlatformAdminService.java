package com.isles.platform.permission;

import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.id.UlidGenerator;
import com.isles.common.security.CurrentUserProvider;
import com.isles.common.time.ClockTimes;
import com.isles.platform.audit.PlatformAuditActions;
import com.isles.platform.audit.PlatformAuditService;
import com.isles.user.entity.User;
import com.isles.user.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 平台管理员与权限校验（docs/11 §7）。
 *
 * <p>角色来源优先级：{@code platform_admin_grants}（正式授权）→ {@code users.platform_role='ADMIN'}
 * → 环境变量白名单（仅用于首次进入后台的引导超管）。解析结果按用户缓存 60 秒，授权变更时主动失效。
 */
@Service
public class PlatformAdminService {

    private static final Duration ROLE_CACHE_TTL = Duration.ofSeconds(60);
    private static final String LEGACY_ADMIN_ROLE = "ADMIN";
    private static final String SUPER_ADMIN_NAME = PlatformRole.SUPER_ADMIN.name();

    private final PlatformAdminGrantMapper grantMapper;
    private final PlatformAdminQueryMapper queryMapper;
    private final UserMapper userMapper;
    private final CurrentUserProvider currentUserProvider;
    private final PlatformAuditService auditService;
    private final Set<String> bootstrapUsernames;
    private final ConcurrentHashMap<String, CachedRole> roleCache = new ConcurrentHashMap<>();

    private record CachedRole(PlatformRole role, long loadedAt) {
    }

    public PlatformAdminService(PlatformAdminGrantMapper grantMapper,
                                PlatformAdminQueryMapper queryMapper,
                                UserMapper userMapper,
                                CurrentUserProvider currentUserProvider,
                                PlatformAuditService auditService,
                                @Value("${app.platform-admin-usernames:}") String bootstrapUsernames) {
        this.grantMapper = grantMapper;
        this.queryMapper = queryMapper;
        this.userMapper = userMapper;
        this.currentUserProvider = currentUserProvider;
        this.auditService = auditService;
        this.bootstrapUsernames = Arrays.stream(bootstrapUsernames == null ? new String[0] : bootstrapUsernames.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    // ------------------------------------------------------------------
    // 鉴权
    // ------------------------------------------------------------------

    /**
     * 解析用户平台角色；无后台权限返回 null。
     */
    public PlatformRole resolveRole(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        long now = System.currentTimeMillis();
        CachedRole cached = roleCache.get(userId);
        if (cached != null && now - cached.loadedAt() < ROLE_CACHE_TTL.toMillis()) {
            return cached.role();
        }
        PlatformRole role = loadRole(userId);
        roleCache.put(userId, new CachedRole(role, now));
        return role;
    }

    public boolean hasPermission(String userId, PlatformPermission permission) {
        PlatformRole role = resolveRole(userId);
        return role != null && role.has(permission);
    }

    /**
     * 校验权限点并返回当前操作人 ID。
     */
    public String requirePermission(PlatformPermission permission) {
        String userId = currentUserProvider.requireUserId();
        if (!hasPermission(userId, permission)) {
            throw new AppException(ErrorCode.FORBIDDEN, "需要平台权限：" + permission.label());
        }
        return userId;
    }

    /**
     * 仅要求「任意平台角色」，用于未声明具体权限点的后台接口。
     */
    public String requireAdmin() {
        String userId = currentUserProvider.requireUserId();
        if (resolveRole(userId) == null) {
            throw new AppException(ErrorCode.FORBIDDEN, "需要平台管理员权限");
        }
        return userId;
    }

    // ------------------------------------------------------------------
    // 管理员授权
    // ------------------------------------------------------------------

    public List<PlatformAdminDtos.AdminView> list() {
        List<PlatformAdminDtos.AdminView> views = new ArrayList<>();
        for (PlatformAdminRow row : queryMapper.selectGrantRows()) {
            views.add(toView(row, false));
        }
        for (PlatformAdminRow row : queryMapper.selectLegacyRows()) {
            views.add(toView(row, true));
        }
        return views;
    }

    @Transactional
    public PlatformAdminDtos.AdminView grant(PlatformAdminDtos.GrantRequest request, String operatorId) {
        PlatformRole role = requireRole(request.role());
        User target = requireUser(request.userId());
        LocalDateTime expiresAt = toUtc(request.expiresAt());

        // 一个用户只保留一条授权：先删后插，避免「过期行 + 新行」并存
        grantMapper.deleteByUserId(target.getId());
        PlatformAdminGrant grant = new PlatformAdminGrant();
        grant.setId(UlidGenerator.generate());
        grant.setUserId(target.getId());
        grant.setRole(role.name());
        grant.setGrantedBy(operatorId);
        grant.setGrantedAt(ClockTimes.nowUtc());
        grant.setExpiresAt(expiresAt);
        grantMapper.insert(grant);
        invalidateRole(target.getId());

        auditService.record(operatorId, null, PlatformAuditActions.ADMIN_GRANT, "ADMIN", target.getId(),
                detail(role, expiresAt));
        return toView(target, grant, false);
    }

    @Transactional
    public PlatformAdminDtos.AdminView changeRole(String userId, PlatformAdminDtos.ChangeRoleRequest request,
                                                  String operatorId) {
        PlatformRole role = requireRole(request.role());
        PlatformAdminGrant existing = grantMapper.selectActiveByUserId(userId);
        if (existing == null) {
            // 引导超管改角色：等价于首次正式授权
            return grant(new PlatformAdminDtos.GrantRequest(userId, role.name(), request.expiresAt()), operatorId);
        }
        PlatformRole previousRole = PlatformRole.fromName(existing.getRole());
        if (previousRole == PlatformRole.SUPER_ADMIN && role != PlatformRole.SUPER_ADMIN) {
            requireAnotherSuperAdmin(userId);
        }
        LocalDateTime expiresAt = toUtc(request.expiresAt());
        existing.setRole(role.name());
        existing.setExpiresAt(expiresAt);
        grantMapper.updateById(existing);
        invalidateRole(userId);

        Map<String, Object> detail = detail(role, expiresAt);
        detail.put("previousRole", previousRole == null ? null : previousRole.name());
        detail.put("note", request.note());
        auditService.record(operatorId, null, PlatformAuditActions.ADMIN_ROLE_CHANGE, "ADMIN", userId, detail);
        User user = userMapper.selectById(userId);
        return toView(user, existing, false);
    }

    @Transactional
    public void revoke(String userId, String operatorId) {
        if (userId.equals(operatorId)) {
            throw new AppException(ErrorCode.ADMIN_SELF_REVOKE_FORBIDDEN);
        }
        PlatformAdminGrant existing = grantMapper.selectActiveByUserId(userId);
        if (existing == null) {
            throw new AppException(ErrorCode.RESOURCE_NOT_FOUND, "该用户没有平台管理员授权");
        }
        if (SUPER_ADMIN_NAME.equalsIgnoreCase(existing.getRole())) {
            requireAnotherSuperAdmin(userId);
        }
        grantMapper.deleteByUserId(userId);
        invalidateRole(userId);
        auditService.record(operatorId, null, PlatformAuditActions.ADMIN_REVOKE, "ADMIN", userId,
                Map.of("role", existing.getRole()));
    }

    public void invalidateRole(String userId) {
        if (userId != null) {
            roleCache.remove(userId);
        }
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private PlatformRole loadRole(String userId) {
        PlatformAdminGrant grant = grantMapper.selectActiveByUserId(userId);
        if (grant != null) {
            PlatformRole role = PlatformRole.fromName(grant.getRole());
            if (role != null) {
                return role;
            }
        }
        User user = userMapper.selectById(userId);
        if (user == null || user.getDeletedAt() != null) {
            return null;
        }
        if (LEGACY_ADMIN_ROLE.equalsIgnoreCase(user.getPlatformRole())) {
            return PlatformRole.SUPER_ADMIN;
        }
        if (user.getUsername() != null && bootstrapUsernames.contains(user.getUsername())) {
            return PlatformRole.SUPER_ADMIN;
        }
        return null;
    }

    /**
     * 至少保留一个超级管理员：按用户去重统计（正式授权 + 引导超管），排除本次被撤销者后必须仍有人。
     */
    private void requireAnotherSuperAdmin(String excludedUserId) {
        long total = queryMapper.countActiveSuperAdmins();
        boolean excludedIsSuperAdmin = isSuperAdmin(excludedUserId);
        if (total - (excludedIsSuperAdmin ? 1 : 0) <= 0) {
            throw new AppException(ErrorCode.ADMIN_LAST_SUPER_ADMIN);
        }
    }

    private boolean isSuperAdmin(String userId) {
        PlatformAdminGrant grant = grantMapper.selectActiveByUserId(userId);
        if (grant != null && SUPER_ADMIN_NAME.equalsIgnoreCase(grant.getRole())) {
            return true;
        }
        User user = userMapper.selectById(userId);
        return user != null && LEGACY_ADMIN_ROLE.equalsIgnoreCase(user.getPlatformRole());
    }

    private PlatformRole requireRole(String roleName) {
        PlatformRole role = PlatformRole.fromName(roleName);
        if (role == null) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "角色取值非法");
        }
        return role;
    }

    private User requireUser(String userId) {
        User user = userMapper.selectById(userId);
        if (user == null || user.getDeletedAt() != null) {
            throw new AppException(ErrorCode.USER_NOT_FOUND);
        }
        return user;
    }

    private LocalDateTime toUtc(Instant expiresAt) {
        if (expiresAt == null) {
            return null;
        }
        if (!expiresAt.isAfter(Instant.now())) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, "授权有效期必须晚于当前时间");
        }
        return ClockTimes.fromInstant(expiresAt);
    }

    private Map<String, Object> detail(PlatformRole role, LocalDateTime expiresAt) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("role", role.name());
        detail.put("expiresAt", expiresAt == null ? null : ClockTimes.toInstant(expiresAt).toString());
        return detail;
    }

    private PlatformAdminDtos.AdminView toView(PlatformAdminRow row, boolean bootstrap) {
        PlatformRole role = PlatformRole.fromName(row.getRole());
        return new PlatformAdminDtos.AdminView(
                row.getId(),
                row.getUserId(),
                row.getUsername(),
                row.getNickname(),
                row.getAvatarUrl(),
                role == null ? row.getRole() : role.name(),
                role == null ? row.getRole() : role.label(),
                row.getGrantedBy(),
                ClockTimes.toInstant(row.getGrantedAt()),
                ClockTimes.toInstant(row.getExpiresAt()),
                bootstrap);
    }

    private PlatformAdminDtos.AdminView toView(User user, PlatformAdminGrant grant, boolean bootstrap) {
        PlatformRole role = PlatformRole.fromName(grant.getRole());
        return new PlatformAdminDtos.AdminView(
                grant.getId(),
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getAvatarUrl(),
                role == null ? grant.getRole() : role.name(),
                role == null ? grant.getRole() : role.label(),
                grant.getGrantedBy(),
                ClockTimes.toInstant(grant.getGrantedAt()),
                ClockTimes.toInstant(grant.getExpiresAt()),
                bootstrap);
    }
}
