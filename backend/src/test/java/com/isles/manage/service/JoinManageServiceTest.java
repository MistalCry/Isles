package com.isles.manage.service;

import com.isles.channel.audit.AuditLogService;
import com.isles.channel.entity.Channel;
import com.isles.channel.entity.ChannelInvite;
import com.isles.channel.entity.ChannelMember;
import com.isles.channel.entity.JoinRequest;
import com.isles.channel.mapper.ChannelInviteMapper;
import com.isles.channel.mapper.JoinRequestMapper;
import com.isles.channel.service.ChannelAccessService;
import com.isles.channel.service.ChannelPermissions;
import com.isles.channel.service.ChannelService;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.manage.dto.JoinRequestResponse;
import com.isles.notification.service.NotificationService;
import com.isles.user.entity.User;
import com.isles.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 加入审批流单测：通过（占邀请次数 + 建成员关系）、拒绝（备注 + 通知）、重复处理与次数耗尽。
 */
class JoinManageServiceTest {

    private ChannelInviteMapper channelInviteMapper;
    private JoinRequestMapper joinRequestMapper;
    private ChannelAccessService accessService;
    private ChannelService channelService;
    private AuditLogService auditLogService;
    private NotificationService notificationService;
    private UserMapper userMapper;
    private JoinManageService service;

    @BeforeEach
    void setUp() {
        channelInviteMapper = mock(ChannelInviteMapper.class);
        joinRequestMapper = mock(JoinRequestMapper.class);
        accessService = mock(ChannelAccessService.class);
        channelService = mock(ChannelService.class);
        auditLogService = mock(AuditLogService.class);
        notificationService = mock(NotificationService.class);
        userMapper = mock(UserMapper.class);
        service = new JoinManageService(channelInviteMapper, joinRequestMapper, accessService, channelService,
                auditLogService, notificationService, userMapper);
    }

    @Test
    void should_approve_pending_request_and_consume_invite_usage() {
        when(accessService.requirePermission("ch-1", "admin-1", ChannelPermissions.INVITE_APPROVE))
                .thenReturn(context());
        when(joinRequestMapper.selectById("req-1")).thenReturn(pendingRequest());
        when(accessService.requireActiveChannel("ch-1")).thenReturn(channel());
        when(accessService.findMember("ch-1", "user-2")).thenReturn(null);
        when(channelInviteMapper.incrementUsedCount("invite-1")).thenReturn(1);
        when(joinRequestMapper.updateById(any(JoinRequest.class))).thenReturn(1);
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(applicant()));

        JoinRequestResponse response = service.approve("ch-1", "req-1", "admin-1");

        assertThat(response.status()).isEqualTo(JoinRequest.Status.APPROVED.name());
        assertThat(response.reviewedBy()).isEqualTo("admin-1");
        verify(channelInviteMapper).incrementUsedCount("invite-1");
        verify(channelService).addMemberDirectly("ch-1", "user-2");
        verify(notificationService).notifySystem(eq("user-2"), any(), eq("ch-1"), any(), any(),
                anyString(), anyString(), anyMap());
        verify(auditLogService).record(anyString(), anyString(), anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void should_reject_approving_already_handled_request() {
        JoinRequest handled = pendingRequest();
        handled.setStatus(JoinRequest.Status.APPROVED.name());
        when(accessService.requirePermission(anyString(), anyString(), anyString())).thenReturn(context());
        when(joinRequestMapper.selectById("req-1")).thenReturn(handled);

        assertThatThrownBy(() -> service.approve("ch-1", "req-1", "admin-1"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.JOIN_REQUEST_HANDLED));
        verify(channelService, never()).addMemberDirectly(anyString(), anyString());
    }

    @Test
    void should_reject_approving_when_invite_usage_exhausted() {
        when(accessService.requirePermission(anyString(), anyString(), anyString())).thenReturn(context());
        when(joinRequestMapper.selectById("req-1")).thenReturn(pendingRequest());
        when(accessService.requireActiveChannel("ch-1")).thenReturn(channel());
        when(accessService.findMember("ch-1", "user-2")).thenReturn(null);
        when(channelInviteMapper.incrementUsedCount("invite-1")).thenReturn(0);

        assertThatThrownBy(() -> service.approve("ch-1", "req-1", "admin-1"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVITE_EXHAUSTED));
        verify(channelService, never()).addMemberDirectly(anyString(), anyString());
    }

    @Test
    void should_reject_pending_request_with_note_and_notify_applicant() {
        when(accessService.requirePermission(anyString(), anyString(), anyString())).thenReturn(context());
        when(joinRequestMapper.selectById("req-1")).thenReturn(pendingRequest());
        when(accessService.requireActiveChannel("ch-1")).thenReturn(channel());
        when(joinRequestMapper.updateById(any(JoinRequest.class))).thenReturn(1);
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(applicant()));

        JoinRequestResponse response = service.reject("ch-1", "req-1", "不符合频道主题", "admin-1");

        assertThat(response.status()).isEqualTo(JoinRequest.Status.REJECTED.name());
        assertThat(response.reviewNote()).isEqualTo("不符合频道主题");
        verify(channelService, never()).addMemberDirectly(anyString(), anyString());
        verify(notificationService).notifySystem(eq("user-2"), any(), eq("ch-1"), any(), any(),
                anyString(), anyString(), anyMap());
    }

    private ChannelAccessService.ChannelAccessContext context() {
        return new ChannelAccessService.ChannelAccessContext(channel(), member("admin-1"), null);
    }

    private Channel channel() {
        Channel channel = new Channel();
        channel.setId("ch-1");
        channel.setName("VRChat 中文社区");
        channel.setOwnerId("owner-1");
        channel.setStatus(Channel.Status.ACTIVE.name());
        return channel;
    }

    private ChannelMember member(String userId) {
        ChannelMember member = new ChannelMember();
        member.setId("member-" + userId);
        member.setChannelId("ch-1");
        member.setUserId(userId);
        member.setRoleId("role-admin");
        member.setStatus(ChannelMember.Status.ACTIVE.name());
        return member;
    }

    private JoinRequest pendingRequest() {
        JoinRequest request = new JoinRequest();
        request.setId("req-1");
        request.setChannelId("ch-1");
        request.setUserId("user-2");
        request.setInviteId("invite-1");
        request.setStatus(JoinRequest.Status.PENDING.name());
        request.setCreatedAt(LocalDateTime.now().minusHours(1));
        return request;
    }

    private User applicant() {
        User user = new User();
        user.setId("user-2");
        user.setUsername("applicant");
        user.setNickname("申请人");
        user.setStatus(User.Status.ACTIVE.name());
        return user;
    }
}
