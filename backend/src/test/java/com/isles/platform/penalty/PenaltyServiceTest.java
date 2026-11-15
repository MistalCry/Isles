package com.isles.platform.penalty;

import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.notification.entity.Notification;
import com.isles.notification.service.NotificationService;
import com.isles.platform.audit.PlatformAuditService;
import com.isles.user.entity.User;
import com.isles.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户处罚单元测试（docs/11 §6.3）：封禁同步用户状态、重复处罚拒绝、撤销恢复、禁言缓存。
 */
class PenaltyServiceTest {

    private UserPenaltyMapper penaltyMapper;
    private UserMapper userMapper;
    private NotificationService notificationService;
    private PlatformAuditService auditService;
    private PenaltyService service;

    @BeforeEach
    void setUp() {
        penaltyMapper = mock(UserPenaltyMapper.class);
        userMapper = mock(UserMapper.class);
        notificationService = mock(NotificationService.class);
        auditService = mock(PlatformAuditService.class);
        service = new PenaltyService(penaltyMapper, userMapper, auditService, notificationService);
    }

    @Test
    void should_ban_user_and_sync_status() {
        when(userMapper.selectById("user-1")).thenReturn(user("user-1"));
        when(penaltyMapper.countActive("user-1", "BAN")).thenReturn(0L);

        PenaltyDtos.PenaltyView view = service.create("user-1",
                new PenaltyDtos.CreatePenaltyRequest("BAN", "发布违规内容", null, null), "admin-1");

        assertThat(view.type()).isEqualTo("BAN");
        assertThat(view.endAt()).isNull();
        assertThat(view.status()).isEqualTo("ACTIVE");
        verify(penaltyMapper).insert(org.mockito.ArgumentMatchers.<UserPenalty>any());
        verify(userMapper).updateById(org.mockito.ArgumentMatchers.<User>argThat(
                user -> "BANNED".equals(user.getStatus())));
        verify(notificationService).notifySystem(eq("user-1"), eq(Notification.Type.SYSTEM), eq(null), eq(null),
                eq(null), eq("账号已被封禁"), any(), eq(null));
        verify(auditService).record(eq("admin-1"), eq(null), eq("platform.penalty.create"), eq("USER"),
                eq("user-1"), any());
    }

    @Test
    void should_reject_duplicate_active_penalty() {
        when(userMapper.selectById("user-1")).thenReturn(user("user-1"));
        when(penaltyMapper.countActive("user-1", "MUTE")).thenReturn(1L);

        assertThatThrownBy(() -> service.create("user-1",
                new PenaltyDtos.CreatePenaltyRequest("MUTE", "刷屏", 24, null), "admin-1"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.PENALTY_ALREADY_ACTIVE));
    }

    @Test
    void should_reject_penalty_on_missing_user() {
        when(userMapper.selectById("ghost")).thenReturn(null);

        assertThatThrownBy(() -> service.create("ghost",
                new PenaltyDtos.CreatePenaltyRequest("WARN", "提醒", null, null), "admin-1"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.USER_NOT_FOUND));
    }

    @Test
    void should_revoke_ban_and_restore_user_status() {
        when(penaltyMapper.selectById("pen-1")).thenReturn(penalty("pen-1", "user-1", "BAN"));
        when(penaltyMapper.revoke("pen-1", "admin-1")).thenReturn(1);
        when(userMapper.selectById("user-1")).thenReturn(user("user-1"));
        when(penaltyMapper.countActive("user-1", "BAN")).thenReturn(0L);

        PenaltyDtos.PenaltyView view = service.revoke("pen-1", "admin-1");

        assertThat(view.status()).isEqualTo("REVOKED");
        verify(userMapper).updateById(org.mockito.ArgumentMatchers.<User>argThat(
                user -> "ACTIVE".equals(user.getStatus())));
        verify(auditService).record(eq("admin-1"), eq(null), eq("platform.penalty.revoke"), eq("USER"),
                eq("user-1"), any());
    }

    @Test
    void should_reject_revoking_finished_penalty() {
        UserPenalty penalty = penalty("pen-1", "user-1", "BAN");
        penalty.setStatus("REVOKED");
        when(penaltyMapper.selectById("pen-1")).thenReturn(penalty);

        assertThatThrownBy(() -> service.revoke("pen-1", "admin-1"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.BUSINESS_RULE_VIOLATION));
    }

    @Test
    void should_cache_mute_check_within_ttl() {
        when(penaltyMapper.countActive("user-1", "MUTE")).thenReturn(1L);

        assertThat(service.isMuted("user-1")).isTrue();
        assertThat(service.isMuted("user-1")).isTrue();

        verify(penaltyMapper, times(1)).countActive("user-1", "MUTE");
    }

    private User user(String id) {
        User user = new User();
        user.setId(id);
        user.setUsername("u-" + id);
        user.setStatus("ACTIVE");
        return user;
    }

    private UserPenalty penalty(String id, String userId, String type) {
        UserPenalty penalty = new UserPenalty();
        penalty.setId(id);
        penalty.setUserId(userId);
        penalty.setType(type);
        penalty.setScope("GLOBAL");
        penalty.setReason("测试");
        penalty.setStatus("ACTIVE");
        penalty.setStartAt(LocalDateTime.now());
        penalty.setCreatedAt(LocalDateTime.now());
        return penalty;
    }
}
