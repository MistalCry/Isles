package com.isles.dm.service;

import com.isles.channel.entity.Channel;
import com.isles.channel.service.ChannelAccessService;
import com.isles.common.dto.MediaItem;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.id.UlidGenerator;
import com.isles.common.json.JsonSupport;
import com.isles.common.pagination.CursorUtils;
import com.isles.common.pagination.PageResult;
import com.isles.common.security.HtmlSanitizer;
import com.isles.common.time.ClockTimes;
import com.isles.dm.dto.ConversationResponse;
import com.isles.dm.dto.CreateConversationRequest;
import com.isles.dm.dto.DmLastMessageResponse;
import com.isles.dm.dto.DmMessageResponse;
import com.isles.dm.dto.DmPeerResponse;
import com.isles.dm.dto.DmReadEvent;
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
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 私信服务（docs/03 §4.12 FR-DM-01~05、§11 决策 5；V4 {@code dm_conversations}/{@code dm_messages}）。
 *
 * <h3>权限</h3>
 * <ul>
 *   <li>会话读写仅限参与者（非参与者统一 404，避免泄露会话存在性）；</li>
 *   <li>任意用户可发起私信，无需好友关系；</li>
 *   <li>存在任一方向拉黑时不可发送（复用 {@link FriendService#assertCanMessage}）；</li>
 *   <li>分享帖子时要求发送方可见、且接收方也有权查看该频道，避免私密内容借私信泄露（docs/07 §1）。</li>
 * </ul>
 *
 * <h3>写入与推送</h3>
 * 消息先落库（REST 同步返回），事务提交后再通过 {@link RealtimePublisher} 推送；
 * 接收方断线时按历史消息游标补拉，推送丢失不影响数据（docs/02 §5.4）。
 */
@Service
@RequiredArgsConstructor
public class DmService {

    private static final Logger log = LoggerFactory.getLogger(DmService.class);

    private static final int TEXT_MAX_LENGTH = 4000;
    private static final int PREVIEW_MAX_LENGTH = 120;
    private static final int POST_TITLE_MAX_LENGTH = 60;

    private static final String PREVIEW_IMAGE = "[图片]";
    private static final String PREVIEW_POST_PREFIX = "分享帖子：";
    private static final String FALLBACK_POST_TITLE = "帖子";

    private static final String EVENT_DM_MESSAGE = "DM_MESSAGE";
    private static final String EVENT_DM_READ = "DM_READ";

    private final DmConversationMapper conversationMapper;
    private final DmMessageMapper messageMapper;
    private final UserMapper userMapper;
    private final UserService userService;
    private final PostService postService;
    private final ChannelAccessService channelAccessService;
    private final FriendService friendService;
    private final RealtimePublisher realtimePublisher;
    private final RealtimeDispatch realtimeDispatch;
    private final JsonSupport jsonSupport;

    // ------------------------------------------------------------------
    // 会话
    // ------------------------------------------------------------------

    /**
     * 打开（不存在则创建）与指定用户的会话，幂等；任意用户可发起（无需好友）。
     */
    @Transactional
    public ConversationResponse openConversation(CreateConversationRequest request, String userId) {
        String peerId = request.resolvePeerId();
        User peer = requirePeer(userId, peerId);
        DmConversation conversation = findOrCreateConversation(userId, peerId);
        return toConversationResponse(conversation, userId, peer);
    }

    /**
     * 我的会话列表：按最后消息时间倒序，{@code (lastMessageAt, id)} 组合游标。
     */
    public PageResult<ConversationResponse> listConversations(String userId, String cursor, Integer limit) {
        int pageSize = CursorUtils.normalizeLimit(limit);
        CursorUtils.HotCursor cursorValue = CursorUtils.parseHotCursor(cursor);
        LocalDateTime cursorAt = cursorValue == null
                ? null
                : LocalDateTime.ofInstant(Instant.ofEpochMilli(cursorValue.score()), ZoneOffset.UTC);
        String cursorId = cursorValue == null ? null : cursorValue.id();
        List<DmConversation> rows = conversationMapper.selectPageByUser(userId, cursorAt, cursorId, pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<DmConversation> page = hasMore ? rows.subList(0, pageSize) : rows;
        Map<String, User> peers = loadUsers(page.stream().map(row -> otherParticipantId(row, userId)).toList());
        List<ConversationResponse> items = page.stream()
                .map(row -> toConversationResponse(row, userId, peers.get(otherParticipantId(row, userId))))
                .toList();
        String nextCursor = null;
        if (hasMore && !items.isEmpty()) {
            DmConversation last = page.get(page.size() - 1);
            nextCursor = CursorUtils.formatHotCursor(activityMillis(last), last.getId());
        }
        return new PageResult<>(items, nextCursor);
    }

    /**
     * 会话详情（仅参与者）。
     */
    public ConversationResponse getConversation(String conversationId, String userId) {
        DmConversation conversation = requireParticipant(conversationId, userId);
        String peerId = otherParticipantId(conversation, userId);
        User peer = loadUsers(List.of(peerId)).get(peerId);
        return toConversationResponse(conversation, userId, peer);
    }

    /**
     * 全站未读私信总数（侧边栏红点）。
     */
    public UnreadCountResponse getUnreadCount(String userId) {
        Long total = conversationMapper.selectTotalUnread(userId);
        return new UnreadCountResponse(total == null ? 0 : total);
    }

    // ------------------------------------------------------------------
    // 消息
    // ------------------------------------------------------------------

    /**
     * 发送消息：落库 → 更新会话最后消息与接收方未读 → 事务提交后推送。
     */
    @Transactional
    public DmMessageResponse sendMessage(String conversationId, SendMessageRequest request, String senderId) {
        DmConversation conversation = requireParticipant(conversationId, senderId);
        String receiverId = otherParticipantId(conversation, senderId);
        friendService.assertCanMessage(senderId, receiverId);

        String type = normalizeType(request.type());
        String clientMessageId = blankToNull(request.clientMessageId());
        if (clientMessageId != null) {
            // 客户端重试幂等：同一 clientMessageId 直接返回已落库消息，不重复插入/推送
            DmMessage existing = messageMapper.selectByClientMessageId(conversationId, senderId, clientMessageId);
            if (existing != null) {
                Map<String, User> users = loadUsers(List.of(existing.getSenderId(), existing.getReceiverId()));
                return toMessageResponse(existing, users.get(existing.getSenderId()));
            }
        }

        LocalDateTime now = ClockTimes.nowUtc();
        String content = null;
        String payload = null;
        String preview;
        switch (type) {
            case "TEXT" -> {
                content = requireText(request.content());
                preview = truncate(content, PREVIEW_MAX_LENGTH);
            }
            case "IMAGE" -> {
                MediaItem media = resolveImageMedia(request);
                content = blankToNull(request.content());
                payload = jsonSupport.toJson(media);
                preview = content == null ? PREVIEW_IMAGE : PREVIEW_IMAGE + " " + truncate(content, PREVIEW_MAX_LENGTH);
            }
            case "POST" -> {
                PostSharePayload post = buildPostShare(request.postId(), senderId, receiverId);
                payload = jsonSupport.toJson(post);
                content = blankToNull(request.content());
                preview = truncate(PREVIEW_POST_PREFIX + post.title(), PREVIEW_MAX_LENGTH);
            }
            default -> throw new AppException(ErrorCode.VALIDATION_ERROR, "type 取值必须为 TEXT、IMAGE 或 POST");
        }

        User sender = requireUserProfile(senderId);

        DmMessage message = new DmMessage();
        message.setId(UlidGenerator.generate());
        message.setConversationId(conversationId);
        message.setSenderId(senderId);
        message.setReceiverId(receiverId);
        message.setType(type);
        message.setContent(content);
        message.setPayload(payload);
        message.setClientMessageId(clientMessageId);
        message.setIsRead(false);
        message.setCreatedAt(now);
        messageMapper.insert(message);

        boolean receiverIsLow = conversation.getUserLowId().equals(receiverId);
        conversationMapper.updateLastMessage(conversationId, message.getId(), senderId, type, preview, now,
                receiverIsLow ? 1 : 0, receiverIsLow ? 0 : 1);
        long receiverUnread = unreadOf(conversation, receiverId) + 1;

        DmMessageResponse messageResponse = toMessageResponse(message, sender);
        realtimeDispatch.execute(() -> realtimePublisher.publishDm(receiverId,
                RealtimeEvent.of(EVENT_DM_MESSAGE, messageResponse)));
        // 发送方本地已通过 REST 响应拿到消息；未读数修正以接收方会话列表/推送为准
        log.info("发送私信 conversationId={} senderId={} receiverId={} type={} receiverUnread={}", conversationId,
                senderId, receiverId, type, receiverUnread);
        return messageResponse;
    }

    /**
     * 历史消息：游标分页（cursor 为上一页最旧一条消息 ID，服务端按 {@code id < cursor} 取更早一页）。
     *
     * <p>返回**时间正序**（oldest → newest）：前端聊天窗口直接追加渲染；加载更早消息时把新一页
     * 前插到已有列表头部即可保持时间序。
     */
    public PageResult<DmMessageResponse> listMessages(String conversationId, String cursor, Integer limit,
                                                      String userId) {
        requireParticipant(conversationId, userId);
        int pageSize = CursorUtils.normalizeLimit(limit);
        String cursorId = CursorUtils.validateIdCursor(cursor);
        // 查询倒序取"离游标最近的 N 条"，再翻转为正序返回
        List<DmMessage> rows = messageMapper.selectHistory(conversationId, cursorId, pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<DmMessage> pageDesc = hasMore ? rows.subList(0, pageSize) : rows;
        String nextCursor = hasMore && !pageDesc.isEmpty() ? pageDesc.get(pageDesc.size() - 1).getId() : null;
        List<DmMessage> pageAsc = new java.util.ArrayList<>(pageDesc);
        java.util.Collections.reverse(pageAsc);
        Map<String, User> senders = loadUsers(pageAsc.stream().map(DmMessage::getSenderId).toList());
        List<DmMessageResponse> items = pageAsc.stream()
                .map(message -> toMessageResponse(message, senders.get(message.getSenderId())))
                .toList();
        return new PageResult<>(items, nextCursor);
    }

    /**
     * 标记会话已读：批量置已读 + 清零当前用户侧未读 + 向对方推送已读回执。
     */
    @Transactional
    public MarkReadResponse markRead(String conversationId, String userId) {
        DmConversation conversation = requireParticipant(conversationId, userId);
        LocalDateTime now = ClockTimes.nowUtc();
        int readCount = messageMapper.markRead(conversationId, userId, now);
        conversationMapper.resetUnread(conversationId, userId, now);
        if (readCount > 0) {
            String peerId = otherParticipantId(conversation, userId);
            DmReadEvent event = new DmReadEvent(conversationId, userId, readCount, ClockTimes.toInstant(now));
            realtimeDispatch.execute(() -> realtimePublisher.publishDm(peerId,
                    RealtimeEvent.of(EVENT_DM_READ, event)));
        }
        return new MarkReadResponse(conversationId, readCount, 0);
    }

    // ------------------------------------------------------------------
    // 会话/权限内部工具
    // ------------------------------------------------------------------

    private User requirePeer(String userId, String peerId) {
        if (peerId == null || peerId.isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "peerId 不能为空");
        }
        if (userId.equals(peerId)) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, "不能给自己发私信");
        }
        return userService.requireActiveById(peerId);
    }

    private DmConversation findOrCreateConversation(String userId, String peerId) {
        String lowId = userId.compareTo(peerId) <= 0 ? userId : peerId;
        String highId = lowId.equals(userId) ? peerId : userId;
        DmConversation existing = conversationMapper.selectByPair(lowId, highId);
        if (existing != null) {
            return existing;
        }
        // 新建会话即「发起私信」：任一方向拉黑都不允许（docs/03 FR-USER-05）。
        // 已存在的会话仍可打开查看历史，仅在发送时拦截。
        friendService.assertCanMessage(userId, peerId);
        LocalDateTime now = ClockTimes.nowUtc();
        DmConversation conversation = new DmConversation();
        conversation.setId(UlidGenerator.generate());
        conversation.setUserLowId(lowId);
        conversation.setUserHighId(highId);
        conversation.setLastMessageAt(now);
        conversation.setUnreadLow(0L);
        conversation.setUnreadHigh(0L);
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        try {
            conversationMapper.insert(conversation);
            return conversation;
        } catch (DuplicateKeyException ex) {
            // 并发创建：唯一约束兜底后回读已有会话，保证接口幂等
            DmConversation concurrent = conversationMapper.selectByPair(lowId, highId);
            if (concurrent == null) {
                throw ex;
            }
            return concurrent;
        }
    }

    private DmConversation requireParticipant(String conversationId, String userId) {
        DmConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null
                || !(userId.equals(conversation.getUserLowId()) || userId.equals(conversation.getUserHighId()))) {
            // 非参与者与不存在统一 404，避免泄露会话存在性（防 IDOR）
            throw new AppException(ErrorCode.CONVERSATION_NOT_FOUND);
        }
        return conversation;
    }

    private String otherParticipantId(DmConversation conversation, String userId) {
        if (userId.equals(conversation.getUserLowId())) {
            return conversation.getUserHighId();
        }
        if (userId.equals(conversation.getUserHighId())) {
            return conversation.getUserLowId();
        }
        throw new AppException(ErrorCode.CONVERSATION_NOT_FOUND);
    }

    private long unreadOf(DmConversation conversation, String userId) {
        long value = userId.equals(conversation.getUserLowId())
                ? nvl(conversation.getUnreadLow())
                : nvl(conversation.getUnreadHigh());
        return Math.max(value, 0);
    }

    private long activityMillis(DmConversation conversation) {
        LocalDateTime at = conversation.getLastMessageAt() != null
                ? conversation.getLastMessageAt()
                : conversation.getCreatedAt();
        return at == null ? 0L : at.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    private ConversationResponse toConversationResponse(DmConversation conversation, String viewerId, User peer) {
        return toConversationResponse(conversation, viewerId, peer, unreadOf(conversation, viewerId));
    }

    private ConversationResponse toConversationResponse(DmConversation conversation, String viewerId, User peer,
                                                        long unreadCount) {
        DmLastMessageResponse last;
        if (conversation.getLastMessageId() != null) {
            last = new DmLastMessageResponse(conversation.getLastMessageId(), conversation.getLastMessageSenderId(),
                    conversation.getLastMessageType(), conversation.getLastMessagePreview(),
                    conversation.getLastMessagePreview(), ClockTimes.toInstant(conversation.getLastMessageAt()));
        } else {
            last = null;
        }
        return new ConversationResponse(
                conversation.getId(),
                toPeerResponse(peer),
                last,
                unreadCount,
                ClockTimes.toInstant(conversation.getLastMessageAt()),
                ClockTimes.toInstant(conversation.getCreatedAt()));
    }

    private DmPeerResponse toPeerResponse(User user) {
        if (user == null) {
            return null;
        }
        return new DmPeerResponse(user.getId(), user.getId(), user.getUsername(), user.getNickname(),
                user.getAvatarUrl());
    }

    // ------------------------------------------------------------------
    // 消息构建内部工具
    // ------------------------------------------------------------------

    private String normalizeType(String rawType) {
        String type = rawType == null ? "" : rawType.trim().toUpperCase();
        return switch (type) {
            case "TEXT", "IMAGE", "POST" -> type;
            default -> throw new AppException(ErrorCode.VALIDATION_ERROR, "type 取值必须为 TEXT、IMAGE 或 POST");
        };
    }

    private String requireText(String content) {
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "文本消息内容不能为空");
        }
        if (text.length() > TEXT_MAX_LENGTH) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "消息内容最长 " + TEXT_MAX_LENGTH + " 个字符");
        }
        return text;
    }

    /**
     * 解析图片消息媒体：优先 {@code media} 数组第一项（前端契约），兼容只传 mediaUrl 的调用。
     */
    private MediaItem resolveImageMedia(SendMessageRequest request) {
        if (request.media() != null) {
            for (MediaItem item : request.media()) {
                if (item != null && item.url() != null && !item.url().isBlank()) {
                    return new MediaItem(
                            item.type() == null ? "IMAGE" : item.type(),
                            item.url(),
                            item.thumbUrl(),
                            item.width(),
                            item.height(),
                            item.durationMs());
                }
            }
        }
        String mediaUrl = blankToNull(request.mediaUrl());
        if (mediaUrl == null) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "图片消息必须提供 media 或 mediaUrl");
        }
        return new MediaItem("IMAGE", mediaUrl, blankToNull(request.thumbUrl()), null, null, null);
    }

    /**
     * 构建帖子分享卡片：发送方必须可见；接收方也必须对该频道可见，避免私密频道内容外泄。
     */
    private PostSharePayload buildPostShare(String postId, String senderId, String receiverId) {
        if (postId == null || postId.isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "分享帖子消息必须提供 postId");
        }
        Post post = postService.requirePublishedPost(postId);
        Channel channel;
        try {
            channel = channelAccessService.requireVisibleChannel(post.getChannelId(), senderId);
        } catch (AppException ex) {
            // 发送方不可见与帖子不存在统一 404，不泄露私密频道存在性
            throw new AppException(ErrorCode.POST_NOT_FOUND);
        }
        try {
            channelAccessService.requireVisibleChannel(post.getChannelId(), receiverId);
        } catch (AppException ex) {
            throw new AppException(ErrorCode.FORBIDDEN, "对方无权查看该帖子，无法分享");
        }
        return new PostSharePayload(
                post.getId(),
                resolvePostTitle(post),
                post.getChannelId(),
                channel.getName(),
                post.getCoverUrl());
    }

    private String resolvePostTitle(Post post) {
        if (post.getTitle() != null && !post.getTitle().isBlank()) {
            return truncate(post.getTitle().trim(), POST_TITLE_MAX_LENGTH);
        }
        String plain = HtmlSanitizer.toPlainText(post.getContent());
        if (plain == null || plain.isBlank()) {
            return FALLBACK_POST_TITLE;
        }
        return truncate(plain.trim(), POST_TITLE_MAX_LENGTH);
    }

    private DmMessageResponse toMessageResponse(DmMessage message, User sender) {
        return new DmMessageResponse(
                message.getId(),
                message.getConversationId(),
                message.getSenderId(),
                sender == null ? null : sender.getNickname(),
                sender == null ? null : sender.getAvatarUrl(),
                message.getReceiverId(),
                message.getType(),
                message.getContent(),
                parseMedia(message),
                parsePost(message),
                Boolean.TRUE.equals(message.getIsRead()),
                ClockTimes.toInstant(message.getReadAt()),
                message.getClientMessageId(),
                ClockTimes.toInstant(message.getCreatedAt()));
    }

    private List<MediaItem> parseMedia(DmMessage message) {
        if (!DmMessage.Type.IMAGE.name().equals(message.getType())) {
            return List.of();
        }
        if (message.getPayload() == null || message.getPayload().isBlank()) {
            return List.of();
        }
        try {
            MediaItem item = jsonSupport.fromJson(message.getPayload(), MediaItem.class);
            return item == null ? List.of() : List.of(item);
        } catch (RuntimeException ex) {
            // 历史脏数据兜底：返回空媒体而不是让整个列表 500
            log.warn("私信图片负载解析失败 messageId={}", message.getId());
            return List.of();
        }
    }

    private PostSharePayload parsePost(DmMessage message) {
        if (!DmMessage.Type.POST.name().equals(message.getType())
                || message.getPayload() == null || message.getPayload().isBlank()) {
            return null;
        }
        try {
            return jsonSupport.fromJson(message.getPayload(), PostSharePayload.class);
        } catch (RuntimeException ex) {
            log.warn("私信分享卡片解析失败 messageId={}", message.getId());
            return null;
        }
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "…";
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private long nvl(Long value) {
        return value == null ? 0L : value;
    }

    private User requireUserProfile(String userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new AppException(ErrorCode.USER_NOT_FOUND);
        }
        return user;
    }

    private Map<String, User> loadUsers(Collection<String> userIds) {
        List<String> ids = userIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(ids).stream().collect(Collectors.toMap(User::getId, Function.identity()));
    }
}
