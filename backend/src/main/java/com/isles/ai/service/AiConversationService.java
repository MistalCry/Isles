package com.isles.ai.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.isles.ai.dto.AiRequests;
import com.isles.ai.dto.AiResponses;
import com.isles.ai.entity.AiConversation;
import com.isles.ai.entity.AiMessage;
import com.isles.ai.mapper.AiConversationMapper;
import com.isles.ai.mapper.AiMessageMapper;
import com.isles.ai.mapper.AiModelConfigMapper;
import com.isles.channel.service.ChannelAccessService;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.id.UlidGenerator;
import com.isles.common.json.JsonSupport;
import com.isles.common.pagination.CursorUtils;
import com.isles.common.pagination.PageResult;
import com.isles.common.time.ClockTimes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * AI 会话与消息应用服务（docs/03 FR-AIQA-01/06/07/08）：
 * 会话 CRUD、消息持久化、编辑提问截断后续消息、重新生成定位最后一条提问。
 *
 * <p>越权防护：所有读写都校验会话归属（own user），不可见与不存在统一 404。
 * 会话列表使用 updated_at 游标分页；消息列表使用 ULID 游标分页（docs/05 §1 分页约定）。
 */
@Service
@RequiredArgsConstructor
public class AiConversationService {

    private static final int DEFAULT_TITLE_LENGTH = 30;
    private static final int MAX_TITLE_LENGTH = 120;
    private static final String NEW_CONVERSATION_TITLE = "新会话";
    private static final String CURSOR_SEPARATOR = "|";

    private final AiConversationMapper conversationMapper;
    private final AiMessageMapper messageMapper;
    private final AiModelConfigMapper modelConfigMapper;
    private final ChannelAccessService accessService;
    private final JsonSupport jsonSupport;

    // ---------------------------------------------------------------------
    // 会话
    // ---------------------------------------------------------------------

    public PageResult<AiResponses.ConversationResponse> listConversations(String userId, String scope, String channelId,
                                                                         String cursor, Integer limit) {
        int pageSize = CursorUtils.normalizeLimit(limit);
        Cursor decoded = decodeConversationCursor(cursor);
        List<AiConversation> rows = conversationMapper.selectPageByUser(userId,
                normalizeScope(scope, false), blankToNull(channelId), decoded.at(), decoded.id(), pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<AiConversation> page = hasMore ? rows.subList(0, pageSize) : rows;
        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            AiConversation last = page.get(page.size() - 1);
            nextCursor = encodeConversationCursor(last);
        }
        return new PageResult<>(page.stream().map(this::toResponse).toList(), nextCursor);
    }

    @Transactional
    public AiResponses.ConversationResponse createConversation(String userId, AiRequests.CreateConversationRequest request) {
        String scope = normalizeScope(request.scope(), true);
        String channelId = blankToNull(request.channelId());
        if (AiConversation.Scope.CHANNEL.name().equals(scope)) {
            if (channelId == null) {
                throw new AppException(ErrorCode.VALIDATION_ERROR, "频道会话必须指定 channelId");
            }
            // 频道可见性校验：私密频道仅成员可发起问答（docs/07 §3）
            accessService.requireVisibleChannel(channelId, userId);
        } else if (AiConversation.Scope.CHANNEL_ASSISTANT.name().equals(scope)) {
            if (channelId == null) {
                throw new AppException(ErrorCode.VALIDATION_ERROR, "频道助手会话必须指定 channelId");
            }
            // 助手私聊要求成员身份：游客/非成员不能占用频道的助手会话
            requireChannelMember(channelId, userId);
        } else {
            channelId = null;
        }
        LocalDateTime now = ClockTimes.nowUtc();
        AiConversation conversation = new AiConversation();
        conversation.setId(UlidGenerator.generate());
        conversation.setUserId(userId);
        conversation.setScope(scope);
        conversation.setChannelId(channelId);
        conversation.setTitle(request.title() == null || request.title().isBlank()
                ? NEW_CONVERSATION_TITLE
                : truncate(request.title().trim(), MAX_TITLE_LENGTH));
        String modelConfigId = blankToNull(request.modelConfigId());
        requireModelConfig(modelConfigId);
        conversation.setModelConfigId(modelConfigId);
        conversation.setMessageCount(0);
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        conversationMapper.insert(conversation);
        return toResponse(conversation);
    }

    /**
     * 模型配置必须是已存在的记录：会话表对 model_config_id 有外键，
     * 直接写入不存在的 id 会抛 SQL 约束异常（500），这里提前转成 400。
     */
    private void requireModelConfig(String modelConfigId) {
        if (modelConfigId == null) {
            return;
        }
        if (modelConfigMapper.selectById(modelConfigId) == null) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "模型配置不存在");
        }
    }

    /**
     * 频道成员校验（频道助手私聊用）：非成员/已退出直接 403。
     */
    public void requireChannelMember(String channelId, String userId) {
        var member = accessService.findMember(channelId, userId);
        if (!ChannelAccessService.isActiveMember(member)) {
            throw new AppException(ErrorCode.NOT_JOINED);
        }
    }

    public AiConversation requireOwnedConversation(String conversationId, String userId) {
        AiConversation conversation = conversationId == null ? null : conversationMapper.selectById(conversationId);
        if (conversation == null || conversation.getDeletedAt() != null
                || !conversation.getUserId().equals(userId)) {
            throw new AppException(ErrorCode.RESOURCE_NOT_FOUND, "AI 会话不存在");
        }
        return conversation;
    }

    public AiResponses.ConversationResponse getConversation(String conversationId, String userId) {
        return toResponse(requireOwnedConversation(conversationId, userId));
    }

    @Transactional
    public AiResponses.ConversationResponse renameConversation(String conversationId, String userId, String title) {
        AiConversation conversation = requireOwnedConversation(conversationId, userId);
        conversation.setTitle(truncate(title.trim(), MAX_TITLE_LENGTH));
        conversation.setUpdatedAt(ClockTimes.nowUtc());
        conversationMapper.updateById(conversation);
        return toResponse(conversation);
    }

    @Transactional
    public void deleteConversation(String conversationId, String userId) {
        AiConversation conversation = requireOwnedConversation(conversationId, userId);
        conversation.setDeletedAt(ClockTimes.nowUtc());
        conversation.setUpdatedAt(ClockTimes.nowUtc());
        conversationMapper.updateById(conversation);
    }

    public void rememberModel(String conversationId, String modelConfigId) {
        if (modelConfigId == null || modelConfigId.isBlank()) {
            return;
        }
        AiConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation != null && !modelConfigId.equals(conversation.getModelConfigId())) {
            conversation.setModelConfigId(modelConfigId);
            conversation.setUpdatedAt(ClockTimes.nowUtc());
            conversationMapper.updateById(conversation);
        }
    }

    // ---------------------------------------------------------------------
    // 消息
    // ---------------------------------------------------------------------

    /**
     * 会话消息：返回最新一页（按时间升序展示）；nextCursor 指向更早消息。
     */
    public PageResult<AiResponses.MessageResponse> listMessages(String conversationId, String userId, String cursor,
                                                                Integer limit) {
        requireOwnedConversation(conversationId, userId);
        int pageSize = CursorUtils.normalizeLimit(limit);
        String cursorId = CursorUtils.validateIdCursor(cursor);
        List<AiMessage> rows = messageMapper.selectNewest(conversationId, cursorId, pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<AiMessage> page = hasMore ? new ArrayList<>(rows.subList(0, pageSize)) : new ArrayList<>(rows);
        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            nextCursor = page.get(page.size() - 1).getId();
        }
        Collections.reverse(page);
        return new PageResult<>(page.stream().map(this::toMessageResponse).toList(), nextCursor);
    }

    /**
     * 追加一条用户提问；首条提问同时生成会话标题。
     */
    @Transactional
    public AiMessage appendUserMessage(String conversationId, String userId, String content, List<String> imageUrls) {
        AiConversation conversation = requireOwnedConversation(conversationId, userId);
        LocalDateTime now = ClockTimes.nowUtc();
        AiMessage message = new AiMessage();
        message.setId(UlidGenerator.generate());
        message.setConversationId(conversationId);
        message.setUserId(userId);
        message.setRole(AiMessage.Role.USER.name());
        message.setContent(content.trim());
        message.setImages(imageUrls == null || imageUrls.isEmpty() ? null : jsonSupport.toJson(imageUrls));
        message.setStatus(AiMessage.Status.COMPLETED.name());
        message.setCreatedAt(now);
        message.setUpdatedAt(now);
        messageMapper.insert(message);
        touchConversation(conversation, now);
        if (conversation.getTitle() == null || conversation.getTitle().isBlank()
                || NEW_CONVERSATION_TITLE.equals(conversation.getTitle())) {
            conversation.setTitle(titleFrom(content));
        }
        conversationMapper.updateById(conversation);
        return message;
    }

    /**
     * 创建助手回答占位消息（STREAMING）：先落库拿到消息 id 再开始流式生成，
     * 前端从 meta 事件拿到同一 id，刷新页面后仍能看到（若异常中断会标记 FAILED/ABORTED）。
     */
    @Transactional
    public AiMessage createAssistantPlaceholder(String conversationId, String userId, String sourcesJson,
                                                String model, String modelConfigId) {
        requireOwnedConversation(conversationId, userId);
        LocalDateTime now = ClockTimes.nowUtc();
        AiMessage message = new AiMessage();
        message.setId(UlidGenerator.generate());
        message.setConversationId(conversationId);
        message.setUserId(userId);
        message.setRole(AiMessage.Role.ASSISTANT.name());
        message.setContent("");
        message.setSources(sourcesJson);
        message.setModel(model);
        message.setModelConfigId(modelConfigId);
        message.setStatus(AiMessage.Status.STREAMING.name());
        message.setCreatedAt(now);
        message.setUpdatedAt(now);
        messageMapper.insert(message);
        return message;
    }

    /**
     * 流式结束后落库回答内容与状态（COMPLETED / ABORTED / FAILED）。
     */
    @Transactional
    public void finishAssistantMessage(String messageId, String content, String reasoningContent, String sourcesJson,
                                       String model, String modelConfigId, Integer tokenIn, Integer tokenOut,
                                       AiMessage.Status status) {
        AiMessage message = messageMapper.selectById(messageId);
        if (message == null) {
            return;
        }
        LocalDateTime now = ClockTimes.nowUtc();
        message.setContent(content == null ? "" : content);
        message.setReasoningContent(reasoningContent == null || reasoningContent.isBlank() ? null : reasoningContent);
        if (sourcesJson != null) {
            message.setSources(sourcesJson);
        }
        message.setModel(model);
        message.setModelConfigId(modelConfigId);
        message.setTokenIn(tokenIn);
        message.setTokenOut(tokenOut);
        message.setStatus(status.name());
        message.setUpdatedAt(now);
        messageMapper.updateById(message);
        AiConversation conversation = conversationMapper.selectById(message.getConversationId());
        if (conversation != null) {
            touchConversation(conversation, now);
            if (modelConfigId != null && !modelConfigId.isBlank()) {
                conversation.setModelConfigId(modelConfigId);
            }
            conversationMapper.updateById(conversation);
        }
    }

    /**
     * 编辑历史提问：更新内容并截断其后的全部消息（docs/03 FR-AIQA-08）。
     */
    @Transactional
    public AiMessage editUserMessage(String messageId, String userId, String content) {
        AiMessage message = requireUserMessageOwned(messageId, userId);
        message.setContent(content.trim());
        message.setUpdatedAt(ClockTimes.nowUtc());
        messageMapper.updateById(message);
        deleteMessagesAfter(message.getConversationId(), messageId);
        return message;
    }

    /**
     * 删除指定消息之后的全部消息（编辑重发/重新生成）。
     */
    @Transactional
    public int deleteMessagesAfter(String conversationId, String messageId) {
        int deleted = messageMapper.delete(Wrappers.<AiMessage>lambdaQuery()
                .eq(AiMessage::getConversationId, conversationId)
                .gt(AiMessage::getId, messageId));
        if (deleted > 0) {
            AiConversation conversation = conversationMapper.selectById(conversationId);
            if (conversation != null) {
                conversation.setMessageCount((int) messageMapper.countByConversation(conversationId));
                conversation.setUpdatedAt(ClockTimes.nowUtc());
                conversationMapper.updateById(conversation);
            }
        }
        return deleted;
    }

    public AiMessage requireUserMessageOwned(String messageId, String userId) {
        AiMessage message = messageId == null ? null : messageMapper.selectById(messageId);
        if (message == null || !AiMessage.Role.USER.name().equals(message.getRole())) {
            throw new AppException(ErrorCode.RESOURCE_NOT_FOUND, "提问消息不存在");
        }
        requireOwnedConversation(message.getConversationId(), userId);
        return message;
    }

    public AiMessage findLastUserMessage(String conversationId) {
        return messageMapper.selectOne(Wrappers.<AiMessage>lambdaQuery()
                .eq(AiMessage::getConversationId, conversationId)
                .eq(AiMessage::getRole, AiMessage.Role.USER.name())
                .orderByDesc(AiMessage::getId)
                .last("LIMIT 1"));
    }

    /**
     * 最近历史消息（按时间升序，最多 limit 条）；用于拼装提示词。
     */
    public List<AiMessage> recentHistory(String conversationId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<AiMessage> rows = messageMapper.selectRecentCompleted(conversationId, limit);
        List<AiMessage> history = new ArrayList<>(rows);
        Collections.reverse(history);
        return history;
    }

    public long countTodayUserQuestions(String userId) {
        LocalDateTime since = LocalDate.now(ZoneOffset.UTC).atStartOfDay();
        return messageMapper.countUserQuestionsSince(userId, since);
    }

    /**
     * 平台数据概览：AI 会话总数（未被软删）。
     */
    public long countConversations() {
        Long count = conversationMapper.selectCount(Wrappers.<AiConversation>lambdaQuery()
                .isNull(AiConversation::getDeletedAt));
        return count == null ? 0L : count;
    }

    /**
     * 平台数据概览：AI 消息总数。
     */
    public long countMessages() {
        Long count = messageMapper.selectCount(null);
        return count == null ? 0L : count;
    }

    // ---------------------------------------------------------------------
    // 响应转换
    // ---------------------------------------------------------------------

    public AiResponses.ConversationResponse toResponse(AiConversation conversation) {
        return new AiResponses.ConversationResponse(
                conversation.getId(),
                conversation.getScope(),
                conversation.getChannelId(),
                conversation.getTitle(),
                conversation.getModelConfigId(),
                conversation.getMessageCount() == null ? 0 : conversation.getMessageCount(),
                ClockTimes.toInstant(conversation.getLastMessageAt()),
                ClockTimes.toInstant(conversation.getCreatedAt()),
                ClockTimes.toInstant(conversation.getUpdatedAt()));
    }

    public AiResponses.MessageResponse toMessageResponse(AiMessage message) {
        return new AiResponses.MessageResponse(
                message.getId(),
                message.getConversationId(),
                message.getRole(),
                message.getContent(),
                message.getReasoningContent(),
                parseImages(message.getImages()),
                parseSources(message.getSources()),
                message.getModel(),
                message.getTokenIn(),
                message.getTokenOut(),
                message.getStatus(),
                ClockTimes.toInstant(message.getCreatedAt()));
    }

    public List<String> parseImages(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        String[] values = jsonSupport.fromJson(json, String[].class);
        return List.of(values);
    }

    public List<AiResponses.SourceResponse> parseSources(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        AiResponses.SourceResponse[] values = jsonSupport.fromJson(json, AiResponses.SourceResponse[].class);
        return List.of(values);
    }

    public String toSourcesJson(List<AiResponses.SourceResponse> sources) {
        return sources == null || sources.isEmpty() ? null : jsonSupport.toJson(sources);
    }

    // ---------------------------------------------------------------------
    // 内部工具
    // ---------------------------------------------------------------------

    private void touchConversation(AiConversation conversation, LocalDateTime now) {
        conversation.setMessageCount((int) messageMapper.countByConversation(conversation.getId()));
        conversation.setLastMessageAt(now);
        conversation.setUpdatedAt(now);
    }

    private String titleFrom(String content) {
        String plain = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        if (plain.isEmpty()) {
            return NEW_CONVERSATION_TITLE;
        }
        return truncate(plain, DEFAULT_TITLE_LENGTH);
    }

    private String normalizeScope(String scope, boolean required) {
        if (scope == null || scope.isBlank()) {
            if (required) {
                return AiConversation.Scope.GLOBAL.name();
            }
            return null;
        }
        String value = scope.trim().toUpperCase(Locale.ROOT);
        if (!AiConversation.Scope.GLOBAL.name().equals(value)
                && !AiConversation.Scope.CHANNEL.name().equals(value)
                && !AiConversation.Scope.CHANNEL_ASSISTANT.name().equals(value)) {
            throw new AppException(ErrorCode.VALIDATION_ERROR,
                    "scope 取值必须为 GLOBAL、CHANNEL 或 CHANNEL_ASSISTANT");
        }
        return value;
    }

    private record Cursor(LocalDateTime at, String id) {
    }

    private Cursor decodeConversationCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return new Cursor(null, null);
        }
        String[] parts = cursor.split("\\" + CURSOR_SEPARATOR, 2);
        if (parts.length != 2) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "无效的分页游标");
        }
        try {
            long millis = Long.parseLong(parts[0]);
            LocalDateTime at = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
            return new Cursor(at, CursorUtils.validateIdCursor(parts[1]));
        } catch (NumberFormatException ex) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "无效的分页游标");
        }
    }

    private String encodeConversationCursor(AiConversation conversation) {
        LocalDateTime at = conversation.getUpdatedAt() == null ? conversation.getCreatedAt() : conversation.getUpdatedAt();
        long millis = at == null ? 0L : at.toInstant(ZoneOffset.UTC).toEpochMilli();
        return millis + CURSOR_SEPARATOR + conversation.getId();
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
