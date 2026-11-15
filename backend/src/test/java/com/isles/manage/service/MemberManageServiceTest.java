package com.isles.manage.service;

import com.isles.channel.audit.AuditLogService;
import com.isles.channel.entity.Channel;
import com.isles.channel.entity.ChannelMember;
import com.isles.channel.entity.Role;
import com.isles.channel.mapper.ChannelMapper;
import com.isles.channel.mapper.ChannelMemberMapper;
import com.isles.channel.mapper.RoleMapper;
import com.isles.channel.service.ChannelAccessService;
import com.isles.channel.service.ChannelPermissions;
import com.isles.channel.service.SystemRoles;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.manage.dto.MemberResponse;
import com.isles.manage.dto.MuteRequest;
import com.isles.notification.service.NotificationService;
import com.isles.user.entity.User;
import com.isles.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

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
 * 成员管理权限矩阵单测：频道主不可操作、超管层级保护、权限不得超越自身、禁言生效。
 */
class MemberManageServiceTest {

    private ChannelMapper channelMapper;
    private ChannelMemberMapper channelMemberMapper;
    private RoleMapper roleMapper;
    private UserMapper userMapper;
    private ChannelAccessService accessService;
    private AuditLogService auditLogService;
    private NotificationService notificationService;
    private MemberManageService service;

    @BeforeEach
    void setUp() {
        channelMapper = mock(ChannelMapper.class);
        channelMemberMapper = mock(ChannelMemberMapper.class);
        roleMapper = mock(RoleMapper.class);
        userMapper = mock(UserMapper.class);
        accessService = mock(ChannelAccessService.class);
        auditLogService = mock(AuditLogService.class);
        notificationService = mock(NotificationService.class);
        service = new MemberManageService(channelMapper, channelMemberMapper, roleMapper, userMapper,
                accessService, auditLogService, notificationService);
    }

    @Test
    void should_reject_operating_channel_owner() {
        Channel channel = channel();
        when(accessService.requirePermission("ch-1", "admin-1", ChannelPermissions.MEMBER_MUTE))
                .thenReturn(context(channel, actor("admin-1"), "channel.member.mute"));

        assertThatThrownBy(() -> service.mute("ch-1", "owner-1", new MuteRequest(3600L), "admin-1"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.CANNOT_OPERATE_OWNER));
        verify(channelMemberMapper, never()).updateById(any(ChannelMember.class));
    }

    @Test
    void should_reject_muting_super_admin_when_actor_is_not_owner() {
        Channel channel = channel();
        when(accessService.requirePermission(anyString(), anyString(), anyString()))
                .thenReturn(context(channel, actor("admin-1"), "channel.member.mute"));
        when(accessService.findMember("ch-1", "admin-2"))
                .thenReturn(member("admin-2", "role-super", ChannelMember.Status.ACTIVE));
        when(roleMapper.selectById("role-super")).thenReturn(role("role-super", SystemRoles.SUPER_ADMIN,
                permissions("channel.member.mute")));

        assertThatThrownBy(() -> service.mute("ch-1", "admin-2", new MuteRequest(3600L), "admin-1"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void should_reject_assigning_role_with_permissions_beyond_actor() {
        Channel channel = channel();
        when(accessService.requirePermission(anyString(), anyString(), anyString()))
                .thenReturn(context(channel, actor("admin-1"),
                        "post.create,comment.create,channel.role.manage"));
        when(accessService.findMember("ch-1", "user-2"))
                .thenReturn(member("user-2", "role-member", ChannelMember.Status.ACTIVE));
        when(roleMapper.selectById("role-member")).thenReturn(role("role-member", SystemRoles.MEMBER,
                permissions("post.create", "comment.create")));
        when(roleMapper.selectById("role-data")).thenReturn(role("role-data", "数据观察员",
                permissions("post.create", "comment.create", "channel.data.view")));
        when(accessService.parsePermissions(any(Role.class))).thenAnswer(invocation -> {
            Role role = invocation.getArgument(0);
            return parsePermissions(role.getPermissions());
        });

        assertThatThrownBy(() -> service.assignRole("ch-1", "user-2", "role-data", "admin-1"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.PERMISSION_EXCEEDED));
        verify(channelMemberMapper, never()).updateById(any(ChannelMember.class));
    }

    @Test
    void should_mute_member_when_permission_and_hierarchy_ok() {
        Channel channel = channel();
        when(accessService.requirePermission(anyString(), anyString(), anyString()))
                .thenReturn(context(channel, actor("owner-1"), "channel.member.mute"));
        when(accessService.findMember("ch-1", "user-2"))
                .thenReturn(member("user-2", "role-member", ChannelMember.Status.ACTIVE));
        when(roleMapper.selectById("role-member")).thenReturn(role("role-member", SystemRoles.MEMBER,
                permissions("post.create", "comment.create")));
        when(channelMemberMapper.updateById(any(ChannelMember.class))).thenReturn(1);
        when(userMapper.selectById("user-2")).thenReturn(user("user-2", "被禁言者"));
        when(accessService.isMuted(any(ChannelMember.class))).thenAnswer(invocation -> {
            ChannelMember value = invocation.getArgument(0);
            return ChannelMember.Status.MUTED.name().equals(value.getStatus());
        });

        MemberResponse response = service.mute("ch-1", "user-2", new MuteRequest(3600L), "owner-1");

        assertThat(response.status()).isEqualTo(ChannelMember.Status.MUTED.name());
        assertThat(response.muted()).isTrue();
        assertThat(response.mutedUntil()).isNotNull();
        verify(channelMemberMapper).updateById(any(ChannelMember.class));
        verify(notificationService).notifySystem(eq("user-2"), any(), eq("ch-1"), any(), any(),
                anyString(), anyString(), anyMap());
        verify(auditLogService).record(anyString(), anyString(), anyString(), anyString(), anyString(), anyMap());
    }

    private ChannelAccessService.ChannelAccessContext context(Channel channel, ChannelMember actor,
                                                              String permissionCsv) {
        return new ChannelAccessService.ChannelAccessContext(channel, actor,
                role(actor.getRoleId(), "操作者身份组", permissions(permissionCsv.split(","))));
    }

    private Channel channel() {
        Channel channel = new Channel();
        channel.setId("ch-1");
        channel.setName("VRChat 中文社区");
        channel.setOwnerId("owner-1");
        channel.setStatus(Channel.Status.ACTIVE.name());
        return channel;
    }

    private ChannelMember actor(String userId) {
        return member(userId, "role-actor", ChannelMember.Status.ACTIVE);
    }

    private ChannelMember member(String userId, String roleId, ChannelMember.Status status) {
        ChannelMember member = new ChannelMember();
        member.setId("member-" + userId);
        member.setChannelId("ch-1");
        member.setUserId(userId);
        member.setRoleId(roleId);
        member.setStatus(status.name());
        member.setJoinedAt(LocalDateTime.now().minusDays(1));
        return member;
    }

    private Role role(String id, String name, String permissionsJson) {
        Role role = new Role();
        role.setId(id);
        role.setChannelId("ch-1");
        role.setName(name);
        role.setPermissions(permissionsJson);
        role.setIsSystem(true);
        return role;
    }

    private String permissions(String... values) {
        return Arrays.stream(values)
                .map(value -> "\"" + value.trim() + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private List<String> parsePermissions(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        String body = json.trim();
        if (body.startsWith("[")) {
            body = body.substring(1);
        }
        if (body.endsWith("]")) {
            body = body.substring(0, body.length() - 1);
        }
        if (body.isBlank()) {
            return List.of();
        }
        return Arrays.stream(body.split(","))
                .map(part -> part.trim().replace("\"", ""))
                .toList();
    }

    private User user(String id, String nickname) {
        User user = new User();
        user.setId(id);
        user.setUsername("user-" + id);
        user.setNickname(nickname);
        user.setStatus(User.Status.ACTIVE.name());
        return user;
    }
}
