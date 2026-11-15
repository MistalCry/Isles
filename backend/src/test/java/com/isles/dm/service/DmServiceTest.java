package com.isles.dm.service;

import com.isles.channel.entity.Channel;
import com.isles.channel.service.ChannelAccessService;
import com.isles.common.dto.MediaItem;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.json.JsonSupport;
import com.isles.common.pagination.PageResult;
import com.isles.dm.dto.ConversationResponse;
import com.isles.dm.dto.CreateConversationRequest;
import com.isles.dm.dto.DmMessageResponse;
import com.isles.dm.dto.MarkReadResponse;
import com.isles.dm.dto.PostSharePayload;
import com.isles.dm.dto.SendMessageRequest;
import com.isles.dm.dto.UnreadCountResponse;
import com.isles.dm.entity.DmConversation;
import com.isles.dm.entity.DmMessage;
import com.isles.dm.mapper.DmConversationMapper;
import com.isles.dm.mapper.DmMessageMapper;
import com.isles.post.entity.Post;
import com.isles.post.service.PostService;
import com.isles.realtime.RealtimeDispatch;
import com.isles.realtime.RealtimePublisher;
import com.isles.realtime.dto.RealtimeEvent;
import com.isles.social.service.FriendService;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 私信服务单元测试（Mockito，不加载 Spring/MySQL）：
 * 参与者权限、拉黑拦截、未读累加、已读清零、分享帖子卡片构建与客户端重试幂等。
 */
class DmServiceTest {

    private DmConversationMapper conversationMapper;
    private DmMessageMapper messageMapper;
    private UserMapper userMapper;
    private UserService userService;
    private PostService postService;
    private ChannelAccessService channelAccessService;
    private FriendService friendService;
    private RealtimePublisher realtimePublisher;
    private DmService dmService;

    @BeforeEach
    void setUp() {
        conversationMapper = mock(DmConversationMapper.class);
        messageMapper = mock(DmMessageMapper.class);
        userMapper = mock(UserMapper.class);
        userService = mock(UserService.class);
        postService = mock(PostService.class);
        channelAccessService = mock(ChannelAccessService.class);
        friendService = mock(FriendService.class);
        realtimePublisher = mock(RealtimePublisher.class);
        dmService = new DmService(conversationMapper, messageMapper, userMapper, userService, postService,
                channelAccessService, friendService, realtimePublisher, new RealtimeDispatch(), new JsonSupport());
    }

    @Test
    void should_send_text_message_and_increment_receiver_unread() {
        DmConversation conversation = conversation();
        when(conversationMapper.selectById("conv-1")).thenReturn(conversation);
        when(userMapper.selectById("user-1")).thenReturn(user("user-1", "sender", "发送者"));
        when(messageMapper.insert(any(DmMessage.class))).thenReturn(1);
        stubUpdateLastMessage();

        DmMessageResponse response = dmService.sendMessage("conv-1",
                new SendMessageRequest("TEXT", "你好", null, null, null, null, null), "user-1");

        assertThat(response.type()).isEqualTo("TEXT");
        assertThat(response.content()).isEqualTo("你好");
        assertThat(response.senderNickname()).isEqualTo("发送者");
        assertThat(response.receiverId()).isEqualTo("user-2");
        assertThat(response.read()).isFalse();

        ArgumentCaptor<DmMessage> messageCaptor = ArgumentCaptor.forClass(DmMessage.class);
        verify(messageMapper).insert(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getConversationId()).isEqualTo("conv-1");
        assertThat(messageCaptor.getValue().getSenderId()).isEqualTo("user-1");
        // 接收方 user-2 是 high 侧，未读只加 unread_high
        verify(conversationMapper).updateLastMessage(eq("conv-1"), anyString(), eq("user-1"), eq("TEXT"),
                eq("你好"), any(LocalDateTime.class), eq(0), eq(1));

        ArgumentCaptor<RealtimeEvent> eventCaptor = ArgumentCaptor.forClass(RealtimeEvent.class);
        verify(realtimePublisher).publishDm(eq("user-2"), eventCaptor.capture());
        assertThat(eventCaptor.getValue().type()).isEqualTo("DM_MESSAGE");
        assertThat(eventCaptor.getValue().data()).isInstanceOf(DmMessageResponse.class);
        assertThat(((DmMessageResponse) eventCaptor.getValue().data()).content()).isEqualTo("你好");
    }

    @Test
    void should_reject_send_when_user_not_participant() {
        DmConversation conversation = conversation();
        conversation.setUserLowId("user-8");
        conversation.setUserHighId("user-9");
        when(conversationMapper.selectById("conv-1")).thenReturn(conversation);

        assertError(() -> dmService.sendMessage("conv-1",
                new SendMessageRequest("TEXT", "你好", null, null, null, null, null), "user-1"),
                ErrorCode.CONVERSATION_NOT_FOUND);
        verify(messageMapper, never()).insert(any(DmMessage.class));
    }

    @Test
    void should_reject_send_when_blocked() {
        when(conversationMapper.selectById("conv-1")).thenReturn(conversation());
        org.mockito.Mockito.doThrow(new AppException(ErrorCode.BLOCKED))
                .when(friendService).assertCanMessage("user-1", "user-2");

        assertError(() -> dmService.sendMessage("conv-1",
                new SendMessageRequest("TEXT", "你好", null, null, null, null, null), "user-1"),
                ErrorCode.BLOCKED);
        verify(messageMapper, never()).insert(any(DmMessage.class));
    }

    @Test
    void should_reject_blank_text_message() {
        when(conversationMapper.selectById("conv-1")).thenReturn(conversation());

        assertError(() -> dmService.sendMessage("conv-1",
                new SendMessageRequest("TEXT", "   ", null, null, null, null, null), "user-1"),
                ErrorCode.VALIDATION_ERROR);
        verify(messageMapper, never()).insert(any(DmMessage.class));
    }

    @Test
    void should_reject_image_without_media() {
        when(conversationMapper.selectById("conv-1")).thenReturn(conversation());

        assertError(() -> dmService.sendMessage("conv-1",
                new SendMessageRequest("IMAGE", null, null, null, null, null, null), "user-1"),
                ErrorCode.VALIDATION_ERROR);
        verify(messageMapper, never()).insert(any(DmMessage.class));
    }

    @Test
    void should_send_image_and_store_media_payload() {
        when(conversationMapper.selectById("conv-1")).thenReturn(conversation());
        when(userMapper.selectById("user-1")).thenReturn(user("user-1", "sender", "发送者"));
        when(messageMapper.insert(any(DmMessage.class))).thenReturn(1);
        stubUpdateLastMessage();
        MediaItem media = new MediaItem("IMAGE", "/uploads/a.png", "/uploads/a_thumb.png", 100, 200, null);

        DmMessageResponse response = dmService.sendMessage("conv-1",
                new SendMessageRequest("IMAGE", "看图", List.of(media), null, null, null, null), "user-1");

        assertThat(response.type()).isEqualTo("IMAGE");
        assertThat(response.content()).isEqualTo("看图");
        assertThat(response.media()).hasSize(1);
        assertThat(response.media().get(0).url()).isEqualTo("/uploads/a.png");
        ArgumentCaptor<DmMessage> captor = ArgumentCaptor.forClass(DmMessage.class);
        verify(messageMapper).insert(captor.capture());
        assertThat(captor.getValue().getPayload()).contains("/uploads/a.png");
    }

    @Test
    void should_build_post_share_payload_for_receiver() {
        when(conversationMapper.selectById("conv-1")).thenReturn(conversation());
        when(userMapper.selectById("user-1")).thenReturn(user("user-1", "sender", "发送者"));
        when(messageMapper.insert(any(DmMessage.class))).thenReturn(1);
        stubUpdateLastMessage();
        when(postService.requirePublishedPost("post-1")).thenReturn(post());
        Channel channel = channel();
        when(channelAccessService.requireVisibleChannel("ch-1", "user-1")).thenReturn(channel);
        when(channelAccessService.requireVisibleChannel("ch-1", "user-2")).thenReturn(channel);

        DmMessageResponse response = dmService.sendMessage("conv-1",
                new SendMessageRequest("POST", null, null, null, null, "post-1", null), "user-1");

        assertThat(response.type()).isEqualTo("POST");
        PostSharePayload share = response.post();
        assertThat(share).isNotNull();
        assertThat(share.postId()).isEqualTo("post-1");
        assertThat(share.title()).isEqualTo("帖子正文内容");
        assertThat(share.channelId()).isEqualTo("ch-1");
        assertThat(share.channelName()).isEqualTo("VRChat 中文社区");
        assertThat(share.coverUrl()).isEqualTo("/uploads/cover.png");

        ArgumentCaptor<DmMessage> captor = ArgumentCaptor.forClass(DmMessage.class);
        verify(messageMapper).insert(captor.capture());
        assertThat(captor.getValue().getPayload()).contains("\"postId\":\"post-1\"")
                .contains("\"channelName\":\"VRChat 中文社区\"");
    }

    @Test
    void should_reject_share_when_receiver_cannot_view_channel() {
        when(conversationMapper.selectById("conv-1")).thenReturn(conversation());
        when(postService.requirePublishedPost("post-1")).thenReturn(post());
        when(channelAccessService.requireVisibleChannel("ch-1", "user-1")).thenReturn(channel());
        when(channelAccessService.requireVisibleChannel("ch-1", "user-2"))
                .thenThrow(new AppException(ErrorCode.CHANNEL_NOT_FOUND));

        assertError(() -> dmService.sendMessage("conv-1",
                new SendMessageRequest("POST", null, null, null, null, "post-1", null), "user-1"),
                ErrorCode.FORBIDDEN);
        verify(messageMapper, never()).insert(any(DmMessage.class));
    }

    @Test
    void should_mark_read_and_push_read_receipt() {
        when(conversationMapper.selectById("conv-1")).thenReturn(conversation());
        when(messageMapper.markRead(eq("conv-1"), eq("user-1"), any(LocalDateTime.class))).thenReturn(3);
        when(conversationMapper.resetUnread(eq("conv-1"), eq("user-1"), any(LocalDateTime.class))).thenReturn(1);

        MarkReadResponse response = dmService.markRead("conv-1", "user-1");

        assertThat(response.readCount()).isEqualTo(3);
        assertThat(response.unreadCount()).isZero();
        verify(conversationMapper).resetUnread(eq("conv-1"), eq("user-1"), any(LocalDateTime.class));
        ArgumentCaptor<RealtimeEvent> eventCaptor = ArgumentCaptor.forClass(RealtimeEvent.class);
        verify(realtimePublisher).publishDm(eq("user-2"), eventCaptor.capture());
        assertThat(eventCaptor.getValue().type()).isEqualTo("DM_READ");
    }

    @Test
    void should_list_conversations_with_peer_and_unread() {
        DmConversation conversation = conversation();
        conversation.setUnreadLow(2L);
        when(conversationMapper.selectPageByUser("user-1", null, null, 21)).thenReturn(List.of(conversation));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user("user-2", "peer", "对方")));

        PageResult<ConversationResponse> page = dmService.listConversations("user-1", null, 20);

        assertThat(page.items()).hasSize(1);
        ConversationResponse item = page.items().get(0);
        assertThat(item.id()).isEqualTo("conv-1");
        assertThat(item.peer().id()).isEqualTo("user-2");
        assertThat(item.peer().nickname()).isEqualTo("对方");
        assertThat(item.unreadCount()).isEqualTo(2);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void should_return_total_unread_count() {
        when(conversationMapper.selectTotalUnread("user-1")).thenReturn(9L);
        UnreadCountResponse response = dmService.getUnreadCount("user-1");
        assertThat(response.total()).isEqualTo(9L);
    }

    @Test
    void should_open_existing_conversation_with_peer_id_alias() {
        when(userService.requireActiveById("user-2")).thenReturn(user("user-2", "peer", "对方"));
        when(conversationMapper.selectByPair("user-1", "user-2")).thenReturn(conversation());

        ConversationResponse viaUserId = dmService.openConversation(new CreateConversationRequest("user-2", null),
                "user-1");
        ConversationResponse viaPeerId = dmService.openConversation(new CreateConversationRequest(null, "user-2"),
                "user-1");

        assertThat(viaUserId.peer().id()).isEqualTo("user-2");
        assertThat(viaPeerId.peer().id()).isEqualTo("user-2");
        verify(conversationMapper, never()).insert(any(DmConversation.class));
    }

    @Test
    void should_reject_conversation_with_self() {
        assertError(() -> dmService.openConversation(new CreateConversationRequest("user-1", null), "user-1"),
                ErrorCode.BUSINESS_RULE_VIOLATION);
    }

    @Test
    void should_return_existing_message_for_duplicate_client_message_id() {
        DmConversation conversation = conversation();
        DmMessage existing = message();
        existing.setClientMessageId("cid-1");
        when(conversationMapper.selectById("conv-1")).thenReturn(conversation);
        when(messageMapper.selectByClientMessageId("conv-1", "user-1", "cid-1")).thenReturn(existing);
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user("user-1", "sender", "发送者")));

        DmMessageResponse response = dmService.sendMessage("conv-1",
                new SendMessageRequest("TEXT", "重试", null, null, null, null, "cid-1"), "user-1");

        assertThat(response.id()).isEqualTo("msg-1");
        verify(messageMapper, never()).insert(any(DmMessage.class));
        verify(realtimePublisher, never()).publishDm(anyString(), any(RealtimeEvent.class));
    }

    @Test
    void should_list_history_messages_in_ascending_order_with_cursor() {
        String cursor = "01M7D1DEWDPJJJ5G9Z2NNW3J74";
        DmMessage newer = message();
        DmMessage older = message();
        older.setId("msg-0");
        older.setContent("更早的消息");
        // Mapper 按 id 倒序返回，服务层翻转成时间正序，前端可直接追加渲染
        when(conversationMapper.selectById("conv-1")).thenReturn(conversation());
        when(messageMapper.selectHistory("conv-1", cursor, 21)).thenReturn(List.of(newer, older));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user("user-1", "sender", "发送者")));

        PageResult<DmMessageResponse> page = dmService.listMessages("conv-1", cursor, 20, "user-1");

        assertThat(page.items()).extracting(DmMessageResponse::id).containsExactly("msg-0", "msg-1");
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void should_reject_history_for_non_participant() {
        DmConversation conversation = conversation();
        conversation.setUserLowId("user-8");
        conversation.setUserHighId("user-9");
        when(conversationMapper.selectById("conv-1")).thenReturn(conversation);

        assertError(() -> dmService.listMessages("conv-1", null, 20, "user-1"), ErrorCode.CONVERSATION_NOT_FOUND);
    }

    // ------------------------------------------------------------------

    private void stubUpdateLastMessage() {
        when(conversationMapper.updateLastMessage(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(LocalDateTime.class), anyInt(), anyInt())).thenReturn(1);
    }

    private void assertError(org.junit.jupiter.api.function.Executable executable, ErrorCode expected) {
        assertThatThrownBy(executable::execute)
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode()).isEqualTo(expected));
    }

    private DmConversation conversation() {
        DmConversation conversation = new DmConversation();
        conversation.setId("conv-1");
        conversation.setUserLowId("user-1");
        conversation.setUserHighId("user-2");
        conversation.setUnreadLow(0L);
        conversation.setUnreadHigh(0L);
        conversation.setLastMessageAt(LocalDateTime.now());
        conversation.setCreatedAt(LocalDateTime.now());
        conversation.setUpdatedAt(LocalDateTime.now());
        return conversation;
    }

    private DmMessage message() {
        DmMessage message = new DmMessage();
        message.setId("msg-1");
        message.setConversationId("conv-1");
        message.setSenderId("user-1");
        message.setReceiverId("user-2");
        message.setType("TEXT");
        message.setContent("历史消息");
        message.setIsRead(false);
        message.setCreatedAt(LocalDateTime.now());
        return message;
    }

    private User user(String id, String username, String nickname) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setNickname(nickname);
        user.setStatus(User.Status.ACTIVE.name());
        return user;
    }

    private Post post() {
        Post post = new Post();
        post.setId("post-1");
        post.setChannelId("ch-1");
        post.setBoardId("board-1");
        post.setAuthorId("author-1");
        post.setContent("<p>帖子正文内容</p>");
        post.setContentFormat(Post.ContentFormat.HTML.name());
        post.setCoverUrl("/uploads/cover.png");
        post.setStatus(Post.Status.PUBLISHED.name());
        post.setCreatedAt(LocalDateTime.now());
        return post;
    }

    private Channel channel() {
        Channel channel = new Channel();
        channel.setId("ch-1");
        channel.setName("VRChat 中文社区");
        channel.setType(Channel.Type.PUBLIC.name());
        channel.setVisibility(Channel.Visibility.PUBLIC_VISIBLE.name());
        channel.setStatus(Channel.Status.ACTIVE.name());
        return channel;
    }
}
