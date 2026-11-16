package com.isles.apps.service;

import com.isles.apps.dto.ActivityDtos;
import com.isles.apps.entity.ActivitySignup;
import com.isles.apps.entity.ChannelActivity;
import com.isles.apps.mapper.ActivitySignupMapper;
import com.isles.apps.mapper.ChannelActivityMapper;
import com.isles.channel.service.ChannelAccessService;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.notification.service.NotificationService;
import com.isles.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 频道活动单元测试（V10）：报名上限、未发布不可报名、提醒幂等、非管理员只看已发布。
 */
class ActivityServiceTest {

    private ChannelActivityMapper activityMapper;
    private ActivitySignupMapper signupMapper;
    private ChannelAccessService accessService;
    private NotificationService notificationService;
    private ActivityService service;

    @BeforeEach
    void setUp() {
        activityMapper = mock(ChannelActivityMapper.class);
        signupMapper = mock(ActivitySignupMapper.class);
        accessService = mock(ChannelAccessService.class);
        notificationService = mock(NotificationService.class);
        service = new ActivityService(activityMapper, signupMapper, accessService, notificationService,
                mock(UserMapper.class));
    }

    @Test
    void should_reject_signup_when_full() {
        ChannelActivity activity = activity("PUBLISHED", 1);
        when(activityMapper.selectById("a1")).thenReturn(activity);
        when(signupMapper.selectByActivityAndUser("a1", "u1")).thenReturn(null);
        when(signupMapper.countGoing("a1")).thenReturn(1L);

        assertThatThrownBy(() -> service.signup("a1", "u1"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode()).isEqualTo(ErrorCode.ACTIVITY_FULL));
        verify(signupMapper, never()).insert(any(ActivitySignup.class));
    }

    @Test
    void should_reject_signup_for_unpublished_activity() {
        when(activityMapper.selectById("a1")).thenReturn(activity("DRAFT", null));

        assertThatThrownBy(() -> service.signup("a1", "u1"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.ACTIVITY_NOT_PUBLISHED));
    }

    @Test
    void should_remind_signups_only_once() {
        ChannelActivity activity = activity("PUBLISHED", 10);
        when(activityMapper.selectDueReminders(any(), any(), anyInt())).thenReturn(List.of(activity));
        when(activityMapper.claimReminder(eq("a1"), any())).thenReturn(1, 0);
        when(signupMapper.selectGoing("a1")).thenReturn(List.of(signup("u1"), signup("u2")));

        int first = service.remindDue();
        // 第二次扫描：claimReminder 返回 0（已被抢占），不再发通知
        int second = service.remindDue();

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        verify(notificationService, times(2)).notifySystem(anyString(), any(), any(), any(), any(),
                eq("活动即将开始"), anyString(), any());
    }

    @Test
    void should_only_show_published_activities_to_non_manager() {
        when(accessService.permissionsOf("ch-1", "u1")).thenReturn(List.of());
        when(activityMapper.countByChannel("ch-1", "PUBLISHED")).thenReturn(0L);
        when(activityMapper.selectByChannel(eq("ch-1"), eq("PUBLISHED"), anyInt(), anyInt())).thenReturn(List.of());

        ActivityDtos.ActivityPage page = service.list("ch-1", "u1", "DRAFT", 1, 20);

        assertThat(page.items()).isEmpty();
        verify(activityMapper).selectByChannel(eq("ch-1"), eq("PUBLISHED"), anyInt(), anyInt());
    }

    private ChannelActivity activity(String status, Integer capacity) {
        ChannelActivity activity = new ChannelActivity();
        activity.setId("a1");
        activity.setChannelId("ch-1");
        activity.setTitle("周末露营");
        activity.setStartAt(LocalDateTime.now().plusMinutes(20));
        activity.setStatus(status);
        activity.setCapacity(capacity);
        activity.setCreatedAt(LocalDateTime.now());
        return activity;
    }

    private ActivitySignup signup(String userId) {
        ActivitySignup signup = new ActivitySignup();
        signup.setId("s-" + userId);
        signup.setActivityId("a1");
        signup.setUserId(userId);
        signup.setStatus(ActivitySignup.Status.GOING.name());
        signup.setCreatedAt(LocalDateTime.now());
        return signup;
    }
}
