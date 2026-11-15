package com.isles.social.service;

import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.pagination.PageResult;
import com.isles.notification.entity.Notification;
import com.isles.notification.service.NotificationService;
import com.isles.realtime.RealtimeDispatch;
import com.isles.realtime.RealtimePublisher;
import com.isles.realtime.dto.RealtimeEvent;
import com.isles.social.dto.FriendRequestResponse;
import com.isles.social.dto.FriendResponse;
import com.isles.social.dto.FriendStatusResponse;
import com.isles.social.entity.FriendRelation;
import com.isles.social.mapper.FriendRelationMapper;
import com.isles.user.entity.User;
import com.isles.user.mapper.UserMapper;
import com.isles.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 好友状态机单元测试（Mockito，不加载 Spring/MySQL）：
 * 申请、防重复、互相申请、同意/拒绝、删除、拉黑与私信权限判定。
 */
class FriendServiceTest {

    private FriendRelationMapper friendRelationMapper;
    private UserMapper userMapper;
    private UserService userService;
    private NotificationService notificationService;
    private RealtimePublisher realtimePublisher;
    private FriendService friendService;

    @BeforeEach
    void setUp() {
        friendRelationMapper = mock(FriendRelationMapper.class);
        userMapper = mock(UserMapper.class);
        userService = mock(UserService.class);
        notificationService = mock(NotificationService.class);
        realtimePublisher = mock(RealtimePublisher.class);
        friendService = new FriendService(friendRelationMapper, userMapper, userService, notificationService,
                realtimePublisher, new RealtimeDispatch());
    }

    @Test
    void should_create_pending_request_and_notify_target() {
        when(userService.requireActiveById("user-2")).thenReturn(user("user-2", "target", "目标用户"));
        when(friendRelationMapper.selectPair("user-1", "user-2")).thenReturn(List.of());
        when(userMapper.selectById("user-1")).thenReturn(user("user-1", "requester", "申请人"));
        when(friendRelationMapper.insert(any(FriendRelation.class))).thenReturn(1);

        FriendRequestResponse response = friendService.sendRequest("user-1", "user-2");

        assertThat(response.direction()).isEqualTo(FriendService.DIRECTION_OUTGOING);
        assertThat(response.status()).isEqualTo(FriendRelation.Status.PENDING.name());
        assertThat(response.fromUser().id()).isEqualTo("user-1");
        assertThat(response.toUser().id()).isEqualTo("user-2");

        ArgumentCaptor<FriendRelation> captor = ArgumentCaptor.forClass(FriendRelation.class);
        verify(friendRelationMapper).insert(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo("user-1");
        assertThat(captor.getValue().getTargetUserId()).isEqualTo("user-2");
        assertThat(captor.getValue().getStatus()).isEqualTo(FriendRelation.Status.PENDING.name());

        verify(notificationService).notifyUser(eq("user-2"), eq(Notification.Type.SYSTEM), isNull(), isNull(),
                isNull(), eq("user-1"), anyString(), anyString());
        ArgumentCaptor<RealtimeEvent> eventCaptor = ArgumentCaptor.forClass(RealtimeEvent.class);
        verify(realtimePublisher).publishNotification(eq("user-2"), eventCaptor.capture());
        assertThat(eventCaptor.getValue().type()).isEqualTo("FRIEND_REQUEST");
    }

    @Test
    void should_reject_duplicate_pending_request() {
        when(userService.requireActiveById("user-2")).thenReturn(user("user-2", "target", "目标用户"));
        when(friendRelationMapper.selectPair("user-1", "user-2"))
                .thenReturn(List.of(relation("req-1", "user-1", "user-2", FriendRelation.Status.PENDING)));

        assertError(() -> friendService.sendRequest("user-1", "user-2"), ErrorCode.FRIEND_REQUEST_PENDING);
        verify(friendRelationMapper, never()).insert(any(FriendRelation.class));
        verify(notificationService, never()).notifyUser(anyString(), any(), any(), any(), any(), any(),
                anyString(), anyString());
    }

    @Test
    void should_reject_request_when_target_blocked_requester() {
        when(userService.requireActiveById("user-2")).thenReturn(user("user-2", "target", "目标用户"));
        when(friendRelationMapper.selectPair("user-1", "user-2"))
                .thenReturn(List.of(relation("req-1", "user-2", "user-1", FriendRelation.Status.BLOCKED)));

        assertError(() -> friendService.sendRequest("user-1", "user-2"), ErrorCode.BLOCKED);
        verify(friendRelationMapper, never()).insert(any(FriendRelation.class));
    }

    @Test
    void should_reject_request_when_already_friends() {
        when(userService.requireActiveById("user-2")).thenReturn(user("user-2", "target", "目标用户"));
        when(friendRelationMapper.selectPair("user-1", "user-2"))
                .thenReturn(List.of(relation("rel-1", "user-1", "user-2", FriendRelation.Status.ACCEPTED)));

        assertError(() -> friendService.sendRequest("user-1", "user-2"), ErrorCode.ALREADY_FRIENDS);
    }

    @Test
    void should_accept_mutual_pending_request_as_friends() {
        FriendRelation incoming = relation("req-2", "user-2", "user-1", FriendRelation.Status.PENDING);
        when(userService.requireActiveById("user-2")).thenReturn(user("user-2", "target", "目标用户"));
        when(friendRelationMapper.selectPair("user-1", "user-2")).thenReturn(List.of(incoming));
        when(userMapper.selectById("user-1")).thenReturn(user("user-1", "requester", "申请人"));
        when(friendRelationMapper.insert(any(FriendRelation.class))).thenReturn(1);

        FriendRequestResponse response = friendService.sendRequest("user-1", "user-2");

        assertThat(response.status()).isEqualTo(FriendRelation.Status.ACCEPTED.name());
        verify(friendRelationMapper).updateById(incoming);
        assertThat(incoming.getStatus()).isEqualTo(FriendRelation.Status.ACCEPTED.name());
        ArgumentCaptor<FriendRelation> captor = ArgumentCaptor.forClass(FriendRelation.class);
        verify(friendRelationMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(FriendRelation.Status.ACCEPTED.name());
        verify(realtimePublisher).publishNotification(eq("user-2"), any(RealtimeEvent.class));
    }

    @Test
    void should_accept_request_and_create_reverse_relation() {
        FriendRelation request = relation("req-1", "user-1", "user-2", FriendRelation.Status.PENDING);
        when(friendRelationMapper.selectById("req-1")).thenReturn(request);
        when(friendRelationMapper.selectPair("user-2", "user-1")).thenReturn(List.of(request));
        when(userMapper.selectById("user-1")).thenReturn(user("user-1", "requester", "申请人"));
        when(userMapper.selectById("user-2")).thenReturn(user("user-2", "addressee", "被申请人"));
        when(friendRelationMapper.insert(any(FriendRelation.class))).thenReturn(1);

        FriendRequestResponse response = friendService.acceptRequest("req-1", "user-2");

        assertThat(response.status()).isEqualTo(FriendRelation.Status.ACCEPTED.name());
        assertThat(response.direction()).isEqualTo(FriendService.DIRECTION_INCOMING);
        assertThat(response.fromUser().id()).isEqualTo("user-1");
        assertThat(response.toUser().id()).isEqualTo("user-2");
        assertThat(request.getStatus()).isEqualTo(FriendRelation.Status.ACCEPTED.name());
        ArgumentCaptor<FriendRelation> captor = ArgumentCaptor.forClass(FriendRelation.class);
        verify(friendRelationMapper).insert(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo("user-2");
        assertThat(captor.getValue().getTargetUserId()).isEqualTo("user-1");
        assertThat(captor.getValue().getStatus()).isEqualTo(FriendRelation.Status.ACCEPTED.name());
        verify(notificationService).notifyUser(eq("user-1"), eq(Notification.Type.SYSTEM), isNull(), isNull(),
                isNull(), eq("user-2"), anyString(), anyString());
    }

    @Test
    void should_forbid_accept_by_non_addressee() {
        FriendRelation request = relation("req-1", "user-1", "user-3", FriendRelation.Status.PENDING);
        when(friendRelationMapper.selectById("req-1")).thenReturn(request);

        assertError(() -> friendService.acceptRequest("req-1", "user-2"), ErrorCode.FORBIDDEN);
        verify(friendRelationMapper, never()).insert(any(FriendRelation.class));
    }

    @Test
    void should_reject_request_and_notify_requester() {
        FriendRelation request = relation("req-1", "user-1", "user-2", FriendRelation.Status.PENDING);
        when(friendRelationMapper.selectById("req-1")).thenReturn(request);
        when(userMapper.selectById("user-1")).thenReturn(user("user-1", "requester", "申请人"));
        when(userMapper.selectById("user-2")).thenReturn(user("user-2", "addressee", "被申请人"));

        FriendRequestResponse response = friendService.rejectRequest("req-1", "user-2");

        assertThat(response.status()).isEqualTo(FriendRelation.Status.REJECTED.name());
        assertThat(request.getStatus()).isEqualTo(FriendRelation.Status.REJECTED.name());
        verify(notificationService).notifyUser(eq("user-1"), eq(Notification.Type.SYSTEM), isNull(), isNull(),
                isNull(), eq("user-2"), anyString(), anyString());
        verify(friendRelationMapper, never()).insert(any(FriendRelation.class));
    }

    @Test
    void should_remove_friend_and_notify_peer() {
        FriendRelation relation = relation("rel-1", "user-1", "user-2", FriendRelation.Status.ACCEPTED);
        when(friendRelationMapper.selectPair("user-1", "user-2")).thenReturn(List.of(relation));
        when(userMapper.selectById("user-1")).thenReturn(user("user-1", "requester", "申请人"));

        friendService.removeFriend("user-1", "user-2");

        verify(friendRelationMapper).deleteNonBlockedBetween("user-1", "user-2");
        verify(realtimePublisher).publishNotification(eq("user-2"), any(RealtimeEvent.class));
    }

    @Test
    void should_block_after_clearing_friendship() {
        when(userService.requireActiveById("user-2")).thenReturn(user("user-2", "target", "目标用户"));
        when(friendRelationMapper.selectPair("user-1", "user-2"))
                .thenReturn(List.of(relation("rel-1", "user-1", "user-2", FriendRelation.Status.ACCEPTED)));
        when(friendRelationMapper.insert(any(FriendRelation.class))).thenReturn(1);

        friendService.block("user-1", "user-2");

        verify(friendRelationMapper).deleteNonBlockedBetween("user-1", "user-2");
        ArgumentCaptor<FriendRelation> captor = ArgumentCaptor.forClass(FriendRelation.class);
        verify(friendRelationMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(FriendRelation.Status.BLOCKED.name());
        assertThat(captor.getValue().getUserId()).isEqualTo("user-1");
    }

    @Test
    void should_forbid_direct_message_when_blocked_either_direction() {
        when(friendRelationMapper.selectPair("user-1", "user-2"))
                .thenReturn(List.of(relation("block-1", "user-2", "user-1", FriendRelation.Status.BLOCKED)));
        assertError(() -> friendService.assertCanMessage("user-1", "user-2"), ErrorCode.BLOCKED);

        when(friendRelationMapper.selectPair("user-1", "user-2"))
                .thenReturn(List.of(relation("block-2", "user-1", "user-2", FriendRelation.Status.BLOCKED)));
        assertError(() -> friendService.assertCanMessage("user-1", "user-2"), ErrorCode.BLOCKED);
    }

    @Test
    void should_allow_direct_message_when_not_blocked() {
        when(friendRelationMapper.selectPair("user-1", "user-2")).thenReturn(List.of());
        friendService.assertCanMessage("user-1", "user-2");
        // 无异常即通过
    }

    @Test
    void should_list_friends_with_profiles() {
        when(friendRelationMapper.selectByUserAndStatus("user-1", FriendRelation.Status.ACCEPTED.name(), null, 21))
                .thenReturn(List.of(relation("rel-1", "user-1", "user-2", FriendRelation.Status.ACCEPTED)));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user("user-2", "friend", "好友")));

        PageResult<FriendResponse> page = friendService.listFriends("user-1", null, 20);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).userId()).isEqualTo("user-2");
        assertThat(page.items().get(0).nickname()).isEqualTo("好友");
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void should_report_friend_status_flags() {
        FriendRelation accepted = relation("rel-1", "user-1", "user-2", FriendRelation.Status.ACCEPTED);
        FriendRelation reverse = relation("rel-2", "user-2", "user-1", FriendRelation.Status.ACCEPTED);
        when(userService.requireActiveById("user-2")).thenReturn(user("user-2", "target", "目标用户"));
        when(friendRelationMapper.selectPair("user-1", "user-2")).thenReturn(List.of(accepted, reverse));

        FriendStatusResponse status = friendService.getStatus("user-1", "user-2");

        assertThat(status.friend()).isTrue();
        assertThat(status.blocked()).isFalse();
        assertThat(status.blockedBy()).isFalse();
        assertThat(status.pendingIncoming()).isFalse();
        assertThat(status.pendingOutgoing()).isFalse();
    }

    @Test
    void should_reject_self_operations() {
        assertError(() -> friendService.sendRequest("user-1", "user-1"), ErrorCode.BUSINESS_RULE_VIOLATION);
        assertError(() -> friendService.block("user-1", "user-1"), ErrorCode.BUSINESS_RULE_VIOLATION);
    }

    // ------------------------------------------------------------------

    private void assertError(org.junit.jupiter.api.function.Executable executable, ErrorCode expected) {
        assertThatThrownBy(executable::execute)
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode()).isEqualTo(expected));
    }

    private FriendRelation relation(String id, String userId, String targetUserId, FriendRelation.Status status) {
        FriendRelation relation = new FriendRelation();
        relation.setId(id);
        relation.setUserId(userId);
        relation.setTargetUserId(targetUserId);
        relation.setStatus(status.name());
        relation.setCreatedAt(LocalDateTime.now());
        relation.setUpdatedAt(LocalDateTime.now());
        return relation;
    }

    private User user(String id, String username, String nickname) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setNickname(nickname);
        user.setStatus(User.Status.ACTIVE.name());
        return user;
    }
}
