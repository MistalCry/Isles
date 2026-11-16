package com.isles.platform.announcement;

import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.notification.entity.Notification;
import com.isles.notification.service.NotificationService;
import com.isles.platform.audit.PlatformAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 公告服务单元测试（docs/11 §5.5）：草稿创建、发布时间补全、窗口校验、发布中禁止删除。
 */
class AnnouncementServiceTest {

    private AnnouncementMapper announcementMapper;
    private PlatformAuditService auditService;
    private NotificationService notificationService;
    private AnnouncementService service;

    @BeforeEach
    void setUp() {
        announcementMapper = mock(AnnouncementMapper.class);
        auditService = mock(PlatformAuditService.class);
        notificationService = mock(NotificationService.class);
        service = new AnnouncementService(announcementMapper, auditService, notificationService);
    }

    @Test
    void should_create_draft_with_defaults() {
        when(announcementMapper.insert(any(Announcement.class))).thenReturn(1);

        AnnouncementDtos.AnnouncementView view = service.create(
                new AnnouncementDtos.SaveAnnouncementRequest("维护通知", "今晚 22 点维护", null, null, null, null),
                "admin-1");

        assertThat(view.status()).isEqualTo("DRAFT");
        assertThat(view.level()).isEqualTo("INFO");
        assertThat(view.placement()).isEqualTo("BANNER");
        assertThat(view.createdBy()).isEqualTo("admin-1");
        verify(announcementMapper).insert(any(Announcement.class));
        verify(auditService).record(eq("admin-1"), eq(null), eq("platform.announcement.create"),
                eq("ANNOUNCEMENT"), any(), any());
    }

    @Test
    void should_reject_end_before_start() {
        Instant start = Instant.parse("2026-11-20T10:00:00Z");
        Instant end = Instant.parse("2026-11-19T10:00:00Z");

        assertThatThrownBy(() -> service.create(
                new AnnouncementDtos.SaveAnnouncementRequest("标题", "内容", null, null, start, end), "admin-1"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.BUSINESS_RULE_VIOLATION));
        verify(announcementMapper, never()).insert(any(Announcement.class));
    }

    @Test
    void should_fill_start_at_when_publishing_without_schedule() {
        Announcement draft = announcement("DRAFT", null);
        when(announcementMapper.selectById("ann-1")).thenReturn(draft);

        AnnouncementDtos.AnnouncementView view = service.publish("ann-1", "admin-1");

        assertThat(view.status()).isEqualTo("PUBLISHED");
        assertThat(view.startAt()).isNotNull();
        verify(announcementMapper).updateById(draft);
    }

    @Test
    void should_archive_published_announcement() {
        Announcement published = announcement("PUBLISHED", LocalDateTime.now());
        when(announcementMapper.selectById("ann-1")).thenReturn(published);

        AnnouncementDtos.AnnouncementView view = service.archive("ann-1", "admin-1");

        assertThat(view.status()).isEqualTo("ARCHIVED");
        verify(auditService).record(eq("admin-1"), eq(null), eq("platform.announcement.archive"),
                eq("ANNOUNCEMENT"), eq("ann-1"), any());
    }

    @Test
    void should_reject_deleting_published_announcement() {
        when(announcementMapper.selectById("ann-1")).thenReturn(announcement("PUBLISHED", LocalDateTime.now()));

        assertThatThrownBy(() -> service.delete("ann-1", "admin-1"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.BUSINESS_RULE_VIOLATION));
        verify(announcementMapper, never()).deleteById("ann-1");
    }

    @Test
    void should_reject_missing_announcement() {
        when(announcementMapper.selectById("ghost")).thenReturn(null);

        assertThatThrownBy(() -> service.publish("ghost", "admin-1"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.ANNOUNCEMENT_NOT_FOUND));
    }


    @Test
    void should_broadcast_inbox_notification_when_placement_is_inbox() {
        Announcement draft = announcement("DRAFT", null);
        draft.setPlacement("INBOX");
        when(announcementMapper.selectById("ann-1")).thenReturn(draft);
        when(notificationService.broadcastSystem(any(), any(), any(), any())).thenReturn(3);

        service.publish("ann-1", "admin-1");

        verify(notificationService).broadcastSystem(eq(Notification.Type.SYSTEM), eq("标题"), eq("内容"), any());
    }

    @Test
    void should_not_broadcast_when_placement_is_banner() {
        when(announcementMapper.selectById("ann-1")).thenReturn(announcement("DRAFT", null));

        service.publish("ann-1", "admin-1");

        verify(notificationService, never()).broadcastSystem(any(), any(), any(), any());
    }

    @Test
    void should_keep_publish_when_broadcast_fails() {
        Announcement draft = announcement("DRAFT", null);
        draft.setPlacement("INBOX");
        when(announcementMapper.selectById("ann-1")).thenReturn(draft);
        when(notificationService.broadcastSystem(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("db down"));

        AnnouncementDtos.AnnouncementView view = service.publish("ann-1", "admin-1");

        // 通知失败不影响公告生效
        assertThat(view.status()).isEqualTo("PUBLISHED");
    }

    private Announcement announcement(String status, LocalDateTime startAt) {
        Announcement announcement = new Announcement();
        announcement.setId("ann-1");
        announcement.setTitle("标题");
        announcement.setContent("内容");
        announcement.setLevel("INFO");
        announcement.setPlacement("BANNER");
        announcement.setStatus(status);
        announcement.setStartAt(startAt);
        announcement.setCreatedAt(LocalDateTime.now());
        announcement.setUpdatedAt(LocalDateTime.now());
        return announcement;
    }
}
