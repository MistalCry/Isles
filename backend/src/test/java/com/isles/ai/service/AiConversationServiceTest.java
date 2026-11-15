package com.isles.ai.service;

import com.isles.ai.dto.AiRequests;
import com.isles.ai.dto.AiResponses;
import com.isles.ai.entity.AiConversation;
import com.isles.ai.entity.AiMessage;
import com.isles.ai.mapper.AiConversationMapper;
import com.isles.ai.mapper.AiMessageMapper;
import com.isles.ai.mapper.AiModelConfigMapper;
import com.isles.channel.entity.Channel;
import com.isles.channel.service.ChannelAccessService;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.json.JsonSupport;
import com.isles.common.pagination.PageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 会话服务单元测试：归属校验、频道范围校验、游标分页、首条提问生成标题、
 * 编辑提问截断后续消息（docs/03 FR-AIQA-06/08）。
 */
class AiConversationServiceTest {

    private AiConversationMapper conversationMapper;
    private AiMessageMapper messageMapper;
    private AiModelConfigMapper modelConfigMapper;
    private ChannelAccessService accessService;
    private AiConversationService service;

    @BeforeEach
    void setUp() {
        conversationMapper = mock(AiConversationMapper.class);
        messageMapper = mock(AiMessageMapper.class);
        modelConfigMapper = mock(AiModelConfigMapper.class);
        accessService = mock(ChannelAccessService.class);
        service = new AiConversationService(conversationMapper, messageMapper, modelConfigMapper, accessService,
                new JsonSupport());
    }

    @Test
    void should_reject_conversation_of_other_user() {
        when(conversationMapper.selectById("conv-1")).thenReturn(conversation("conv-1", "other-user", "GLOBAL", null));

        assertThatThrownBy(() -> service.requireOwnedConversation("conv-1", "user-1"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void should_reject_channel_scope_without_channel_id() {
        assertThatThrownBy(() -> service.createConversation("user-1",
                new AiRequests.CreateConversationRequest("CHANNEL", null, null, null)))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void should_create_channel_conversation_after_visibility_check() {
        when(accessService.requireVisibleChannel("ch-1", "user-1")).thenReturn(channel("ch-1"));
        when(conversationMapper.insert(any(AiConversation.class))).thenReturn(1);

        AiResponses.ConversationResponse response = service.createConversation("user-1",
                new AiRequests.CreateConversationRequest("CHANNEL", "ch-1", null, null));

        assertThat(response.scope()).isEqualTo("CHANNEL");
        assertThat(response.channelId()).isEqualTo("ch-1");
        assertThat(response.title()).isEqualTo("新会话");
        verify(accessService).requireVisibleChannel("ch-1", "user-1");
    }

    @Test
    void should_page_conversations_with_cursor() {
        when(conversationMapper.selectPageByUser(eq("user-1"), any(), any(), any(), any(), eq(3)))
                .thenReturn(List.of(
                        conversation("conv-3", "user-1", "GLOBAL", null),
                        conversation("conv-2", "user-1", "GLOBAL", null),
                        conversation("conv-1", "user-1", "GLOBAL", null)));

        PageResult<AiResponses.ConversationResponse> page = service.listConversations("user-1", null, null,
                null, 2);

        assertThat(page.items()).hasSize(2);
        assertThat(page.nextCursor()).contains("|");
    }

    @Test
    void should_set_title_from_first_user_question() {
        AiConversation conversation = conversation("conv-1", "user-1", "GLOBAL", null);
        conversation.setTitle("新会话");
        when(conversationMapper.selectById("conv-1")).thenReturn(conversation);
        when(messageMapper.insert(any(AiMessage.class))).thenReturn(1);
        when(messageMapper.countByConversation("conv-1")).thenReturn(1L);
        when(conversationMapper.updateById(any(AiConversation.class))).thenReturn(1);

        AiMessage message = service.appendUserMessage("conv-1", "user-1", "  VRChat 最近有什么活动？  ", List.of());

        assertThat(message.getRole()).isEqualTo("USER");
        assertThat(message.getContent()).isEqualTo("VRChat 最近有什么活动？");
        ArgumentCaptor<AiConversation> captor = ArgumentCaptor.forClass(AiConversation.class);
        verify(conversationMapper).updateById(captor.capture());
        assertThat(captor.getValue().getTitle()).startsWith("VRChat 最近有什么活动");
        assertThat(captor.getValue().getMessageCount()).isEqualTo(1);
    }

    @Test
    void should_truncate_messages_after_edited_question() {
        AiMessage message = new AiMessage();
        message.setId("msg-2");
        message.setConversationId("conv-1");
        message.setUserId("user-1");
        message.setRole("USER");
        message.setContent("旧提问");
        message.setStatus("COMPLETED");
        when(messageMapper.selectById("msg-2")).thenReturn(message);
        when(conversationMapper.selectById("conv-1")).thenReturn(conversation("conv-1", "user-1", "GLOBAL", null));
        when(messageMapper.updateById(any(AiMessage.class))).thenReturn(1);
        when(messageMapper.delete(any())).thenReturn(3);
        when(messageMapper.countByConversation("conv-1")).thenReturn(1L);

        AiMessage edited = service.editUserMessage("msg-2", "user-1", "新的提问");

        assertThat(edited.getContent()).isEqualTo("新的提问");
        verify(messageMapper).delete(any());
        verify(messageMapper).updateById(any(AiMessage.class));
    }

    @Test
    void should_not_delete_when_message_belongs_to_another_conversation_owner() {
        AiMessage message = new AiMessage();
        message.setId("msg-2");
        message.setConversationId("conv-1");
        message.setRole("USER");
        when(messageMapper.selectById("msg-2")).thenReturn(message);
        when(conversationMapper.selectById("conv-1")).thenReturn(conversation("conv-1", "other-user", "GLOBAL", null));

        assertThatThrownBy(() -> service.editUserMessage("msg-2", "user-1", "新的提问"))
                .isInstanceOf(AppException.class);
        verify(messageMapper, never()).delete(any());
    }

    @Test
    void should_count_today_questions() {
        when(messageMapper.countUserQuestionsSince(eq("user-1"), any())).thenReturn(3L);

        assertThat(service.countTodayUserQuestions("user-1")).isEqualTo(3L);
    }

    @Test
    void should_parse_sources_json() {
        List<AiResponses.SourceResponse> sources = service.parseSources(
                "[{\"id\":\"p1\",\"title\":\"帖子\",\"channelId\":\"ch-1\",\"channelName\":\"频道\","
                        + "\"excerpt\":\"摘要\",\"url\":\"/posts/p1\"}]");

        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).url()).isEqualTo("/posts/p1");
    }

    private AiConversation conversation(String id, String userId, String scope, String channelId) {
        AiConversation conversation = new AiConversation();
        conversation.setId(id);
        conversation.setUserId(userId);
        conversation.setScope(scope);
        conversation.setChannelId(channelId);
        conversation.setMessageCount(0);
        conversation.setCreatedAt(LocalDateTime.now());
        conversation.setUpdatedAt(LocalDateTime.now());
        return conversation;
    }

    private Channel channel(String id) {
        Channel channel = new Channel();
        channel.setId(id);
        channel.setName("测试频道");
        channel.setType("PUBLIC");
        channel.setVisibility("PUBLIC_VISIBLE");
        channel.setStatus("ACTIVE");
        return channel;
    }
}
