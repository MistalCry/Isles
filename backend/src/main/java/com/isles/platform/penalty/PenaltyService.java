package com.isles.platform.penalty;

import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.id.UlidGenerator;
import com.isles.common.time.ClockTimes;
import com.isles.notification.entity.Notification;
import com.isles.notification.service.NotificationService;
import com.isles.platform.audit.PlatformAuditActions;
import com.isles.platform.audit.PlatformAuditService;
import com.isles.user.entity.User;
import com.isles.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户处罚服务（docs/11 §6.3）。
 *
 * <p>处罚类型：{@code BAN} 全站封禁（同步 {@code users.status=BANNED}，登录被拒）、
 * {@code MUTE} 全站禁言（写内容接口被拦截）、{@code WARN} 仅通知与留痕。
 *
 * <p>禁言判定按用户缓存 60 秒，创建/撤销时主动失效；封禁在登录时直接查库（低频且必须准确）。
 */
@Service
@RequiredArgsConstructor
public class PenaltyService {

    private static final Duration MUTE_CACHE_TTL = Duration.ofSeconds(60);
    private static final int MAX_LIST_SIZE = 100;

    private final UserPenaltyMapper penaltyMapper;
    private final UserMapper userMapper;
    private final PlatformAuditService auditService;
    private final NotificationService notificationService;

    private final ConcurrentHashMap<String, CachedMute> muteCache = new ConcurrentHashMap<>();

    private record CachedMute(boolean muted, long loadedAt) {
    }

    @Transactional
    public PenaltyDtos.PenaltyView create(String userId, PenaltyDtos.CreatePenaltyRequest request, String operatorId) {
        UserPenalty.Type type = parseType(request.type());
        User target = userMapper.selectById(userId);
        if (target == null || target.getDeletedAt() != null) {
            throw new AppException(ErrorCode.USER_NOT_FOUND);
        }
        if (penaltyMapper.countActive(userId, type.name()) > 0) {
            throw new AppException(ErrorCode.PENALTY_ALREADY_ACTIVE,
                    "该用户已有生效中的" + labelOf(type) + "处罚");
        }

        LocalDateTime now = ClockTimes.nowUtc();
        LocalDateTime endAt = request.durationHours() == null
                ? null
                : now.plus(Duration.ofHours(request.durationHours()));

        UserPenalty penalty = new UserPenalty();
        penalty.setId(UlidGenerator.generate());
        penalty.setUserId(userId);
        penalty.setType(type.name());
        penalty.setScope(UserPenalty.Scope.GLOBAL.name());
        penalty.setReason(request.reason().trim());
        penalty.setReportId(request.reportId());
        penalty.setOperatorId(operatorId);
        penalty.setStartAt(now);
        penalty.setEndAt(endAt);
        penalty.setStatus(UserPenalty.Status.ACTIVE.name());
        penalty.setCreatedAt(now);
        penaltyMapper.insert(penalty);

        if (type == UserPenalty.Type.BAN) {
            applyBanStatus(target, User.Status.BANNED);
        }
        invalidateMute(userId);
        notifyPenalty(userId, type, penalty.getReason(), endAt);
        auditService.record(operatorId, null, PlatformAuditActions.PENALTY_CREATE, "USER", userId,
                penaltyDetail(penalty));
        return toView(penalty);
    }

    @Transactional
    public PenaltyDtos.PenaltyView revoke(String penaltyId, String operatorId) {
        UserPenalty penalty = penaltyMapper.selectById(penaltyId);
        if (penalty == null) {
            throw new AppException(ErrorCode.PENALTY_NOT_FOUND);
        }
        if (!UserPenalty.Status.ACTIVE.name().equals(penalty.getStatus())) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, "该处罚已结束，无需撤销");
        }
        int updated = penaltyMapper.revoke(penaltyId, operatorId);
        if (updated == 0) {
            // 并发撤销：以数据库为准，不重复写通知与审计
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, "该处罚已结束，无需撤销");
        }
        penalty.setStatus(UserPenalty.Status.REVOKED.name());
        penalty.setRevokedBy(operatorId);
        penalty.setRevokedAt(ClockTimes.nowUtc());

        if (UserPenalty.Type.BAN.name().equals(penalty.getType())) {
            User target = userMapper.selectById(penalty.getUserId());
            if (target != null && penaltyMapper.countActive(penalty.getUserId(), UserPenalty.Type.BAN.name()) == 0) {
                applyBanStatus(target, User.Status.ACTIVE);
            }
        }
        invalidateMute(penalty.getUserId());
        notificationService.notifySystem(penalty.getUserId(), Notification.Type.SYSTEM, null, null, null,
                "处罚已撤销", "平台已撤销对你的" + labelOf(parseType(penalty.getType())) + "处罚", null);
        auditService.record(operatorId, null, PlatformAuditActions.PENALTY_REVOKE, "USER", penalty.getUserId(),
                Map.of("penaltyId", penaltyId, "type", penalty.getType()));
        return toView(penalty);
    }

    public List<PenaltyDtos.PenaltyView> listByUser(String userId) {
        return penaltyMapper.selectByUser(userId, MAX_LIST_SIZE).stream().map(this::toView).toList();
    }

    /**
     * 查询用户某类生效中的处罚；没有则返回 null（后台「解封」等动作使用）。
     */
    public PenaltyDtos.PenaltyView findActive(String userId, String type) {
        UserPenalty.Type penaltyType = parseType(type);
        UserPenalty penalty = penaltyMapper.selectActive(userId, penaltyType.name());
        return penalty == null ? null : toView(penalty);
    }

    /**
     * 是否处于全站禁言（写内容接口的拦截依据）。
     */
    public boolean isMuted(String userId) {
        if (userId == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        CachedMute cached = muteCache.get(userId);
        if (cached != null && now - cached.loadedAt() < MUTE_CACHE_TTL.toMillis()) {
            return cached.muted();
        }
        boolean muted = penaltyMapper.countActive(userId, UserPenalty.Type.MUTE.name()) > 0;
        muteCache.put(userId, new CachedMute(muted, now));
        return muted;
    }

    /**
     * 是否处于封禁（登录校验用，不走缓存）。
     */
    public boolean isBanned(String userId) {
        return penaltyMapper.countActive(userId, UserPenalty.Type.BAN.name()) > 0;
    }

    private void applyBanStatus(User user, User.Status status) {
        user.setStatus(status.name());
        user.setUpdatedAt(ClockTimes.nowUtc());
        userMapper.updateById(user);
    }

    private void notifyPenalty(String userId, UserPenalty.Type type, String reason, LocalDateTime endAt) {
        String duration = endAt == null ? "永久" : "至 " + ClockTimes.toInstant(endAt);
        String title = switch (type) {
            case BAN -> "账号已被封禁";
            case MUTE -> "你已被禁言";
            case WARN -> "你收到一条平台警告";
        };
        String content = "原因：" + reason + "；期限：" + duration;
        Notification.Type notificationType = type == UserPenalty.Type.MUTE
                ? Notification.Type.MEMBER_MUTED
                : Notification.Type.SYSTEM;
        notificationService.notifySystem(userId, notificationType, null, null, null, title, content, null);
    }

    private Map<String, Object> penaltyDetail(UserPenalty penalty) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("penaltyId", penalty.getId());
        detail.put("type", penalty.getType());
        detail.put("reason", penalty.getReason());
        detail.put("endAt", penalty.getEndAt() == null ? null : ClockTimes.toInstant(penalty.getEndAt()).toString());
        detail.put("reportId", penalty.getReportId());
        return detail;
    }

    private UserPenalty.Type parseType(String type) {
        try {
            return UserPenalty.Type.valueOf(type);
        } catch (IllegalArgumentException ex) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "处罚类型取值必须为 BAN/MUTE/WARN");
        }
    }

    private String labelOf(UserPenalty.Type type) {
        return switch (type) {
            case BAN -> "封禁";
            case MUTE -> "禁言";
            case WARN -> "警告";
        };
    }

    private PenaltyDtos.PenaltyView toView(UserPenalty penalty) {
        return new PenaltyDtos.PenaltyView(
                penalty.getId(),
                penalty.getUserId(),
                penalty.getType(),
                penalty.getScope(),
                penalty.getChannelId(),
                penalty.getReason(),
                penalty.getReportId(),
                penalty.getOperatorId(),
                ClockTimes.toInstant(penalty.getStartAt()),
                ClockTimes.toInstant(penalty.getEndAt()),
                penalty.getStatus(),
                penalty.getRevokedBy(),
                ClockTimes.toInstant(penalty.getRevokedAt()),
                ClockTimes.toInstant(penalty.getCreatedAt()));
    }

    private void invalidateMute(String userId) {
        if (userId != null) {
            muteCache.remove(userId);
        }
    }
}
