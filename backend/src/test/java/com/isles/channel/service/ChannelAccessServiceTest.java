package com.isles.channel.service;

import com.isles.channel.entity.Channel;
import com.isles.channel.entity.ChannelMember;
import com.isles.channel.entity.Role;
import com.isles.channel.mapper.ChannelMapper;
import com.isles.channel.mapper.ChannelMemberMapper;
import com.isles.channel.mapper.RoleMapper;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 频道可见性与权限校验单元测试（防越权核心逻辑）。
 */
class ChannelAccessServiceTest {

    private ChannelMapper channelMapper;
    private ChannelMemberMapper channelMemberMapper;
    private RoleMapper roleMapper;
    private ChannelAccessService accessService;

    @BeforeEach
    void setUp() {
        channelMapper = mock(ChannelMapper.class);
        channelMemberMapper = mock(ChannelMemberMapper.class);
        roleMapper = mock(RoleMapper.class);
        accessService = new ChannelAccessService(channelMapper, channelMemberMapper, roleMapper);
    }

    @Test
    void should_allow_anonymous_read_of_public_channel() {
        Channel channel = channel(Channel.Type.PUBLIC, Channel.Visibility.PUBLIC_VISIBLE);
        when(channelMapper.selectById("ch-1")).thenReturn(channel);

        Channel result = accessService.requireVisibleChannel("ch-1", null);

        assertThat(result.getId()).isEqualTo("ch-1");
    }

    @Test
    void should_hide_members_only_channel_from_non_member() {
        Channel channel = channel(Channel.Type.PRIVATE, Channel.Visibility.MEMBERS_ONLY);
        when(channelMapper.selectById("ch-1")).thenReturn(channel);
        when(channelMemberMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> accessService.requireVisibleChannel("ch-1", "user-2"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.CHANNEL_NOT_FOUND));
    }

    @Test
    void should_allow_member_read_of_members_only_channel() {
        Channel channel = channel(Channel.Type.PRIVATE, Channel.Visibility.MEMBERS_ONLY);
        when(channelMapper.selectById("ch-1")).thenReturn(channel);
        when(channelMemberMapper.selectOne(any())).thenReturn(member(ChannelMember.Status.ACTIVE, null));

        Channel result = accessService.requireVisibleChannel("ch-1", "user-2");

        assertThat(result.getId()).isEqualTo("ch-1");
    }

    @Test
    void should_reject_interaction_when_not_member() {
        Channel channel = channel(Channel.Type.PUBLIC, Channel.Visibility.PUBLIC_VISIBLE);
        when(channelMapper.selectById("ch-1")).thenReturn(channel);
        when(channelMemberMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> accessService.requireInteractionPermission("ch-1", "user-2",
                ChannelPermissions.POST_CREATE))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode()).isEqualTo(ErrorCode.NOT_JOINED));
    }

    @Test
    void should_reject_interaction_when_member_muted() {
        Channel channel = channel(Channel.Type.PUBLIC, Channel.Visibility.PUBLIC_VISIBLE);
        when(channelMapper.selectById("ch-1")).thenReturn(channel);
        when(channelMemberMapper.selectOne(any())).thenReturn(member(ChannelMember.Status.MUTED, null));

        assertThatThrownBy(() -> accessService.requireInteractionPermission("ch-1", "user-2",
                ChannelPermissions.POST_CREATE))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode()).isEqualTo(ErrorCode.CHANNEL_MUTED));
    }

    @Test
    void should_reject_interaction_when_mute_not_expired() {
        Channel channel = channel(Channel.Type.PUBLIC, Channel.Visibility.PUBLIC_VISIBLE);
        when(channelMapper.selectById("ch-1")).thenReturn(channel);
        when(channelMemberMapper.selectOne(any())).thenReturn(
                member(ChannelMember.Status.ACTIVE, LocalDateTime.now().plusDays(1)));

        assertThatThrownBy(() -> accessService.requireInteractionPermission("ch-1", "user-2",
                ChannelPermissions.POST_CREATE))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode()).isEqualTo(ErrorCode.CHANNEL_MUTED));
    }

    @Test
    void should_allow_interaction_when_role_has_permission() {
        Channel channel = channel(Channel.Type.PUBLIC, Channel.Visibility.PUBLIC_VISIBLE);
        ChannelMember member = member(ChannelMember.Status.ACTIVE, null);
        member.setRoleId("role-1");
        when(channelMapper.selectById("ch-1")).thenReturn(channel);
        when(channelMemberMapper.selectOne(any())).thenReturn(member);
        when(roleMapper.selectById("role-1")).thenReturn(
                role("[\"post.create\",\"comment.create\"]", "ch-1"));

        ChannelAccessService.ChannelAccessContext context = accessService
                .requireInteractionPermission("ch-1", "user-2", ChannelPermissions.POST_CREATE);

        assertThat(context.isMember()).isTrue();
        assertThat(context.role().getId()).isEqualTo("role-1");
    }

    @Test
    void should_reject_interaction_when_role_lacks_permission() {
        Channel channel = channel(Channel.Type.PUBLIC, Channel.Visibility.PUBLIC_VISIBLE);
        ChannelMember member = member(ChannelMember.Status.ACTIVE, null);
        member.setRoleId("role-1");
        when(channelMapper.selectById("ch-1")).thenReturn(channel);
        when(channelMemberMapper.selectOne(any())).thenReturn(member);
        when(roleMapper.selectById("role-1")).thenReturn(role("[]", "ch-1"));

        assertThatThrownBy(() -> accessService.requireInteractionPermission("ch-1", "user-2",
                ChannelPermissions.POST_CREATE))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    private Channel channel(Channel.Type type, Channel.Visibility visibility) {
        Channel channel = new Channel();
        channel.setId("ch-1");
        channel.setChannelNo(10_000_001L);
        channel.setName("VRChat 中文社区");
        channel.setOwnerId("owner-1");
        channel.setType(type.name());
        channel.setVisibility(visibility.name());
        channel.setStatus(Channel.Status.ACTIVE.name());
        channel.setMemberCount(10L);
        channel.setPostCount(5L);
        channel.setCreatedAt(LocalDateTime.now());
        return channel;
    }

    private ChannelMember member(ChannelMember.Status status, LocalDateTime mutedUntil) {
        ChannelMember member = new ChannelMember();
        member.setId("member-1");
        member.setChannelId("ch-1");
        member.setUserId("user-2");
        member.setRoleId("role-1");
        member.setStatus(status.name());
        member.setMutedUntil(mutedUntil);
        member.setJoinedAt(LocalDateTime.now());
        return member;
    }

    private Role role(String permissions, String channelId) {
        Role role = new Role();
        role.setId("role-1");
        role.setChannelId(channelId);
        role.setName(SystemRoles.MEMBER);
        role.setPermissions(permissions);
        return role;
    }
}
