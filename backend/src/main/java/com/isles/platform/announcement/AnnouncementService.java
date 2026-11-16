package com.isles.platform.announcement;

import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.id.UlidGenerator;
import com.isles.common.time.ClockTimes;
import com.isles.notification.entity.Notification;
import com.isles.notification.service.NotificationService;
import com.isles.platform.audit.PlatformAuditActions;
import com.isles.platform.audit.PlatformAuditService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台公告服务（docs/11 §5.5）：草稿 → 发布 → 归档；公开接口只返回生效中的公告。
 *
 * <p>状态机：{@code DRAFT} →（发布）{@code PUBLISHED} →（归档）{@code ARCHIVED}，归档后可重新发布。
 * 已发布的公告必须先归档才能删除，避免误删正在展示的内容。
 *
 * <p>发布副作用：{@code placement=INBOX} 的公告会向全站活跃用户各写一条系统通知（分批写入）。
 * {@code BANNER}/{@code MODAL} 是前台展示位，由公告组件渲染，不再重复写站内信（避免双重打扰）。
 */
@Service
@RequiredArgsConstructor
public class AnnouncementService {

    private static final Logger log = LoggerFactory.getLogger(AnnouncementService.class);
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int PUBLIC_LIMIT = 5;

    private final AnnouncementMapper announcementMapper;
    private final PlatformAuditService auditService;
    private final NotificationService notificationService;

    public AnnouncementDtos.AnnouncementPage list(String status, Integer page, Integer size) {
        int pageNo = page == null || page <= 0 ? 1 : page;
        int pageSize = size == null || size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        String statusFilter = blankToNull(status);
        long total = announcementMapper.countPage(statusFilter);
        List<AnnouncementDtos.AnnouncementView> items = announcementMapper
                .selectPage(statusFilter, pageSize, (pageNo - 1) * pageSize).stream()
                .map(this::toView)
                .toList();
        return new AnnouncementDtos.AnnouncementPage(items, total, pageNo, pageSize);
    }

    /**
     * 公开公告：只返回已发布且在生效窗口内的记录，最多 5 条。
     */
    public List<AnnouncementDtos.AnnouncementView> active() {
        return announcementMapper.selectActive(ClockTimes.nowUtc(), PUBLIC_LIMIT).stream()
                .map(this::toView)
                .toList();
    }

    @Transactional
    public AnnouncementDtos.AnnouncementView create(AnnouncementDtos.SaveAnnouncementRequest request,
                                                    String operatorId) {
        LocalDateTime startAt = ClockTimes.fromInstant(request.startAt());
        LocalDateTime endAt = ClockTimes.fromInstant(request.endAt());
        validateWindow(startAt, endAt);

        LocalDateTime now = ClockTimes.nowUtc();
        Announcement announcement = new Announcement();
        announcement.setId(UlidGenerator.generate());
        announcement.setTitle(request.title().trim());
        announcement.setContent(request.content().trim());
        announcement.setLevel(normalizeLevel(request.level()));
        announcement.setPlacement(normalizePlacement(request.placement()));
        announcement.setStatus(Announcement.Status.DRAFT.name());
        announcement.setStartAt(startAt);
        announcement.setEndAt(endAt);
        announcement.setCreatedBy(operatorId);
        announcement.setCreatedAt(now);
        announcement.setUpdatedAt(now);
        announcementMapper.insert(announcement);

        audit(PlatformAuditActions.ANNOUNCEMENT_CREATE, announcement, operatorId, Map.of("status", "DRAFT"));
        log.info("平台公告：新建 id={} title={}", announcement.getId(), announcement.getTitle());
        return toView(announcement);
    }

    @Transactional
    public AnnouncementDtos.AnnouncementView update(String id, AnnouncementDtos.SaveAnnouncementRequest request,
                                                    String operatorId) {
        Announcement announcement = require(id);
        LocalDateTime startAt = ClockTimes.fromInstant(request.startAt());
        LocalDateTime endAt = ClockTimes.fromInstant(request.endAt());
        validateWindow(startAt, endAt);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("beforeTitle", announcement.getTitle());
        detail.put("beforeStatus", announcement.getStatus());

        announcement.setTitle(request.title().trim());
        announcement.setContent(request.content().trim());
        announcement.setLevel(normalizeLevel(request.level()));
        announcement.setPlacement(normalizePlacement(request.placement()));
        announcement.setStartAt(startAt);
        announcement.setEndAt(endAt);
        announcement.setUpdatedAt(ClockTimes.nowUtc());
        announcementMapper.updateById(announcement);

        detail.put("afterTitle", announcement.getTitle());
        audit(PlatformAuditActions.ANNOUNCEMENT_UPDATE, announcement, operatorId, detail);
        return toView(announcement);
    }

    @Transactional
    public AnnouncementDtos.AnnouncementView publish(String id, String operatorId) {
        Announcement announcement = require(id);
        if (Announcement.Status.PUBLISHED.name().equals(announcement.getStatus())) {
            return toView(announcement);
        }
        announcement.setStatus(Announcement.Status.PUBLISHED.name());
        if (announcement.getStartAt() == null) {
            announcement.setStartAt(ClockTimes.nowUtc());
        }
        announcement.setUpdatedAt(ClockTimes.nowUtc());
        announcementMapper.updateById(announcement);
        audit(PlatformAuditActions.ANNOUNCEMENT_PUBLISH, announcement, operatorId,
                Map.of("startAt", String.valueOf(announcement.getStartAt())));
        broadcastToInbox(announcement);
        log.info("平台公告：发布 id={}", id);
        return toView(announcement);
    }

    /**
     * 站内信：仅 {@code placement=INBOX} 才写；通知失败不回滚发布（公告已对外生效，通知可补发）。
     */
    private void broadcastToInbox(Announcement announcement) {
        if (!Announcement.Placement.INBOX.name().equals(announcement.getPlacement())) {
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("announcementId", announcement.getId());
            payload.put("placement", announcement.getPlacement());
            payload.put("level", announcement.getLevel());
            int sent = notificationService.broadcastSystem(Notification.Type.SYSTEM, announcement.getTitle(),
                    announcement.getContent(), payload);
            log.info("平台公告：站内信群发完成 id={} sent={}", announcement.getId(), sent);
        } catch (RuntimeException ex) {
            log.warn("平台公告：站内信群发失败 id={}", announcement.getId(), ex);
        }
    }

    @Transactional
    public AnnouncementDtos.AnnouncementView archive(String id, String operatorId) {
        Announcement announcement = require(id);
        announcement.setStatus(Announcement.Status.ARCHIVED.name());
        announcement.setUpdatedAt(ClockTimes.nowUtc());
        announcementMapper.updateById(announcement);
        audit(PlatformAuditActions.ANNOUNCEMENT_ARCHIVE, announcement, operatorId, Map.of());
        log.info("平台公告：归档 id={}", id);
        return toView(announcement);
    }

    @Transactional
    public void delete(String id, String operatorId) {
        Announcement announcement = require(id);
        if (Announcement.Status.PUBLISHED.name().equals(announcement.getStatus())) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, "已发布的公告请先归档再删除");
        }
        announcementMapper.deleteById(id);
        audit(PlatformAuditActions.ANNOUNCEMENT_DELETE, announcement, operatorId,
                Map.of("title", announcement.getTitle()));
        log.info("平台公告：删除 id={}", id);
    }

    private Announcement require(String id) {
        Announcement announcement = announcementMapper.selectById(id);
        if (announcement == null) {
            throw new AppException(ErrorCode.ANNOUNCEMENT_NOT_FOUND);
        }
        return announcement;
    }

    private void validateWindow(LocalDateTime startAt, LocalDateTime endAt) {
        if (startAt != null && endAt != null && !endAt.isAfter(startAt)) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, "结束时间必须晚于开始时间");
        }
    }

    private String normalizeLevel(String level) {
        return level == null || level.isBlank() ? Announcement.Level.INFO.name() : level;
    }

    private String normalizePlacement(String placement) {
        return placement == null || placement.isBlank() ? Announcement.Placement.BANNER.name() : placement;
    }

    private void audit(String action, Announcement announcement, String operatorId, Map<String, Object> detail) {
        Map<String, Object> merged = new LinkedHashMap<>(detail);
        merged.put("title", announcement.getTitle());
        merged.put("level", announcement.getLevel());
        merged.put("placement", announcement.getPlacement());
        auditService.record(operatorId, null, action, "ANNOUNCEMENT", announcement.getId(), merged);
    }

    private AnnouncementDtos.AnnouncementView toView(Announcement announcement) {
        return new AnnouncementDtos.AnnouncementView(
                announcement.getId(),
                announcement.getTitle(),
                announcement.getContent(),
                announcement.getLevel(),
                announcement.getPlacement(),
                announcement.getStatus(),
                toInstant(announcement.getStartAt()),
                toInstant(announcement.getEndAt()),
                announcement.getCreatedBy(),
                ClockTimes.toInstant(announcement.getCreatedAt()),
                ClockTimes.toInstant(announcement.getUpdatedAt()));
    }

    private Instant toInstant(LocalDateTime value) {
        return ClockTimes.toInstant(value);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
