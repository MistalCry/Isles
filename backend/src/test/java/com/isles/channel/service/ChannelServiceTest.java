package com.isles.channel.service;

import com.isles.channel.dto.ChannelResponse;
import com.isles.channel.dto.CreateChannelRequest;
import com.isles.channel.dto.InviteAcceptResponse;
import com.isles.channel.entity.Board;
import com.isles.channel.entity.Channel;
import com.isles.channel.entity.ChannelInvite;
import com.isles.channel.entity.ChannelMember;
import com.isles.channel.entity.JoinRequest;
import com.isles.channel.entity.Role;
import com.isles.channel.mapper.BoardMapper;
import com.isles.channel.mapper.ChannelInviteMapper;
import com.isles.channel.mapper.ChannelMapper;
import com.isles.channel.mapper.ChannelMemberMapper;
import com.isles.channel.mapper.JoinRequestMapper;
import com.isles.channel.mapper.RoleMapper;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.json.JsonSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 频道服务单元测试：创建频道的初始化数据、加入私密频道被拒、加入公开频道、邀请链接需审批。
 */
class ChannelServiceTest {

    private ChannelMapper channelMapper;
    private BoardMapper boardMapper;
    private RoleMapper roleMapper;
    private ChannelMemberMapper channelMemberMapper;
    private ChannelInviteMapper channelInviteMapper;
    private JoinRequestMapper joinRequestMapper;
    private ChannelAccessService accessService;
    private ChannelService channelService;

    @BeforeEach
    void setUp() {
        channelMapper = mock(ChannelMapper.class);
        boardMapper = mock(BoardMapper.class);
        roleMapper = mock(RoleMapper.class);
        channelMemberMapper = mock(ChannelMemberMapper.class);
        channelInviteMapper = mock(ChannelInviteMapper.class);
        joinRequestMapper = mock(JoinRequestMapper.class);
        accessService = mock(ChannelAccessService.class);
        channelService = new ChannelService(channelMapper, boardMapper, roleMapper, channelMemberMapper,
                channelInviteMapper, joinRequestMapper, accessService, new JsonSupport());
    }

    @Test
    void should_create_channel_with_default_board_four_roles_and_owner_member() {
        when(channelMapper.selectCount(any())).thenReturn(0L);
        when(channelMapper.insert(any(Channel.class))).thenReturn(1);
        when(boardMapper.insert(any(Board.class))).thenReturn(1);
        when(roleMapper.insert(any(Role.class))).thenReturn(1);
        when(channelMemberMapper.insert(any(ChannelMember.class))).thenReturn(1);

        ChannelResponse response = channelService.createChannel(
                new CreateChannelRequest("VRChat 中文社区", "/uploads/av.png", null, "VRChat 玩家交流",
                        "PUBLIC", "PUBLIC_VISIBLE"),
                "owner-1");

        assertThat(response.id()).hasSize(26);
        assertThat(response.channelNo()).isGreaterThan(0L);
        assertThat(response.joined()).isTrue();
        assertThat(response.myRoleName()).isEqualTo(SystemRoles.OWNER);
        assertThat(response.memberCount()).isEqualTo(1L);
        verify(boardMapper, times(1)).insert(any(Board.class));
        verify(roleMapper, times(4)).insert(any(Role.class));
        verify(channelMemberMapper, times(1)).insert(any(ChannelMember.class));
    }

    @Test
    void should_reject_join_when_channel_is_private() {
        Channel channel = channel(Channel.Type.PRIVATE, Channel.Visibility.MEMBERS_ONLY);
        when(accessService.requireVisibleChannel("ch-1", "user-2")).thenReturn(channel);

        assertThatThrownBy(() -> channelService.joinChannel("ch-1", "user-2"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.BUSINESS_RULE_VIOLATION));
        verify(channelMemberMapper, never()).insert(any(ChannelMember.class));
    }

    @Test
    void should_join_public_channel_and_update_member_count() {
        Channel channel = channel(Channel.Type.PUBLIC, Channel.Visibility.PUBLIC_VISIBLE);
        Role memberRole = new Role();
        memberRole.setId("role-member");
        memberRole.setChannelId("ch-1");
        memberRole.setName(SystemRoles.MEMBER);
        when(accessService.requireVisibleChannel("ch-1", "user-2")).thenReturn(channel);
        when(accessService.findMember("ch-1", "user-2")).thenReturn(null);
        when(roleMapper.selectOne(any())).thenReturn(memberRole);
        when(channelMemberMapper.insert(any(ChannelMember.class))).thenReturn(1);
        when(channelMapper.addMemberCount("ch-1", 1)).thenReturn(1);

        ChannelResponse response = channelService.joinChannel("ch-1", "user-2");

        assertThat(response.joined()).isTrue();
        assertThat(response.myRoleName()).isEqualTo(SystemRoles.MEMBER);
        assertThat(response.memberCount()).isEqualTo(11L);
        verify(channelMemberMapper).insert(any(ChannelMember.class));
        verify(channelMapper).addMemberCount("ch-1", 1);
    }

    @Test
    void should_create_join_request_when_invite_requires_approval() {
        ChannelInvite invite = new ChannelInvite();
        invite.setId("invite-1");
        invite.setChannelId("ch-1");
        invite.setRequiresApproval(true);
        invite.setExpiresAt(LocalDateTime.now().plusDays(1));
        invite.setUsedCount(0);
        when(channelInviteMapper.selectOne(any())).thenReturn(invite);
        when(accessService.requireActiveChannel("ch-1")).thenReturn(channel(Channel.Type.PRIVATE,
                Channel.Visibility.MEMBERS_ONLY));
        when(accessService.findMember("ch-1", "user-2")).thenReturn(null);
        when(joinRequestMapper.selectCount(any())).thenReturn(0L);
        when(joinRequestMapper.insert(any(JoinRequest.class))).thenReturn(1);

        InviteAcceptResponse response = channelService.acceptInvite("raw-token", "user-2");

        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.joinRequestId()).isNotBlank();
        verify(joinRequestMapper).insert(any(JoinRequest.class));
        verify(channelMemberMapper, never()).insert(any(ChannelMember.class));
    }

    @Test
    void should_reject_expired_invite() {
        ChannelInvite invite = new ChannelInvite();
        invite.setId("invite-1");
        invite.setChannelId("ch-1");
        invite.setRequiresApproval(false);
        invite.setExpiresAt(LocalDateTime.now().minusDays(1));
        invite.setUsedCount(0);
        when(channelInviteMapper.selectOne(any())).thenReturn(invite);

        assertThatThrownBy(() -> channelService.acceptInvite("raw-token", "user-2"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVITE_EXPIRED));
    }

    private Channel channel(Channel.Type type, Channel.Visibility visibility) {
        Channel channel = new Channel();
        channel.setId("ch-1");
        channel.setChannelNo(10_000_001L);
        channel.setName("VRChat 中文社区");
        channel.setAvatarUrl("/uploads/av.png");
        channel.setOwnerId("owner-1");
        channel.setType(type.name());
        channel.setVisibility(visibility.name());
        channel.setStatus(Channel.Status.ACTIVE.name());
        channel.setMemberCount(10L);
        channel.setPostCount(5L);
        channel.setCreatedAt(LocalDateTime.now());
        return channel;
    }
}
