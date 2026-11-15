package com.isles.post.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.isles.analytics.service.ChannelViewService;
import com.isles.channel.audit.AuditLogService;
import com.isles.channel.audit.ChannelAuditLog;
import com.isles.channel.entity.Board;
import com.isles.channel.entity.Channel;
import com.isles.channel.mapper.BoardMapper;
import com.isles.channel.mapper.ChannelMapper;
import com.isles.channel.service.ChannelAccessService;
import com.isles.channel.service.ChannelPermissions;
import com.isles.common.dto.MediaItem;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.id.UlidGenerator;
import com.isles.common.media.MediaPayloads;
import com.isles.common.pagination.CursorUtils;
import com.isles.common.pagination.PageResult;
import com.isles.common.security.HtmlSanitizer;
import com.isles.common.time.ClockTimes;
import com.isles.interaction.service.InteractionService;
import com.isles.post.dto.CreatePostRequest;
import com.isles.post.dto.UpdatePostRequest;
import com.isles.post.dto.PostResponse;
import com.isles.post.entity.Post;
import com.isles.post.entity.PostMedia;
import com.isles.post.mapper.PostMapper;
import com.isles.post.mapper.PostMediaMapper;
import com.isles.user.entity.User;
import com.isles.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 帖子应用服务（docs/03 §5.2 PostQueryService 的 M1 实现）：发帖、频道帖子流、详情。
 *
 * <p>权限集中：发帖走 {@link ChannelAccessService#requireInteractionPermission}（成员 + 禁言 + 权限点）；
 * 列表/详情走可见性校验。列表统一批量加载作者/频道/板块/媒体/点赞状态，避免 N+1。
 */
@Service
@RequiredArgsConstructor
public class PostService {

    private static final String TAB_LATEST = "latest";
    private static final String TAB_HOT = "hot";
    private static final String TAB_ESSENCE = "essence";

    private final PostMapper postMapper;
    private final PostMediaMapper postMediaMapper;
    private final ChannelMapper channelMapper;
    private final BoardMapper boardMapper;
    private final UserMapper userMapper;
    private final ChannelAccessService accessService;
    private final InteractionService interactionService;
    private final ChannelViewService channelViewService;
    private final AuditLogService auditLogService;
    private final com.isles.channel.service.MentionService mentionService;
    private final com.isles.platform.wordfilter.SensitiveWordService sensitiveWordService;

    /**
     * 发帖：校验成员身份、禁言状态、板块归属与媒体合法性；事务内写帖子、媒体与计数。
     */
    @Transactional
    public PostResponse createPost(String channelId, CreatePostRequest request, String userId) {
        accessService.requireInteractionPermission(channelId, userId, ChannelPermissions.POST_CREATE);

        Board board = boardMapper.selectById(request.boardId());
        if (board == null) {
            throw new AppException(ErrorCode.BOARD_NOT_FOUND);
        }
        if (!channelId.equals(board.getChannelId())) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, "板块不属于该频道");
        }

        String contentFormat = request.contentFormat() == null
                ? Post.ContentFormat.PLAIN.name()
                : request.contentFormat();
        String content = request.content();
        if (Post.ContentFormat.HTML.name().equals(contentFormat)) {
            // 富文本必须服务端白名单净化（docs/07 §4），禁止原始 HTML 直出
            content = HtmlSanitizer.sanitize(content);
        }
        if (content == null || content.isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "正文不能为空");
        }
        List<MediaItem> media = MediaPayloads.normalize(request.media());
        if (request.title() != null && request.title().length() > 200) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "标题最长 200 个字符");
        }

        LocalDateTime now = ClockTimes.nowUtc();
        Post post = new Post();
        post.setId(UlidGenerator.generate());
        post.setChannelId(channelId);
        post.setBoardId(board.getId());
        post.setAuthorId(userId);
        post.setTitle(request.title() == null || request.title().isBlank() ? null : request.title().trim());
        post.setContent(content);
        post.setContentFormat(contentFormat);
        post.setCoverUrl(request.coverUrl());
        post.setViewCount(0L);
        post.setLikeCount(0L);
        post.setCommentCount(0L);
        post.setShareCount(0L);
        post.setIsPinned(false);
        post.setIsEssence(false);
        post.setStatus(Post.Status.PUBLISHED.name());
        post.setCreatedAt(now);
        post.setUpdatedAt(now);
        postMapper.insert(post);

        for (int index = 0; index < media.size(); index++) {
            MediaItem item = media.get(index);
            PostMedia postMedia = new PostMedia();
            postMedia.setId(UlidGenerator.generate());
            postMedia.setPostId(post.getId());
            postMedia.setMediaType(item.type());
            postMedia.setUrl(item.url());
            postMedia.setThumbUrl(item.thumbUrl());
            postMedia.setWidth(item.width());
            postMedia.setHeight(item.height());
            postMedia.setDurationMs(item.durationMs());
            postMedia.setSort(index);
            postMedia.setCreatedAt(now);
            postMediaMapper.insert(postMedia);
        }

        channelMapper.addPostCount(channelId, 1);
        boardMapper.addPostCount(board.getId(), 1);
        auditLogService.record(channelId, userId, ChannelAuditLog.Action.POST_CREATE, "POST", post.getId(),
                Map.of("boardId", board.getId()));
        // @提及：通知被提及的成员；提及频道助手会触发助手回复
        mentionService.notifyMentions(channelId, post.getId(), null, content, userId);
        // 敏感词：REVIEW 模式只记录命中，不阻断发布（docs/11 §5.5）
        sensitiveWordService.scanAndRecord("POST", post.getId(), userId, content,
                Post.ContentFormat.HTML.name().equals(contentFormat));
        return toResponses(List.of(post), userId).get(0);
    }

    /**
     * 编辑帖子（FR-POST-07）：仅作者本人；富文本重新走白名单净化，媒体可整体替换。
     */
    @Transactional
    public PostResponse updatePost(String postId, UpdatePostRequest request, String userId) {
        Post post = requirePublishedPost(postId);
        if (!post.getAuthorId().equals(userId)) {
            throw new AppException(ErrorCode.FORBIDDEN, "只能编辑自己的帖子");
        }

        String contentFormat = request.contentFormat() == null ? post.getContentFormat() : request.contentFormat();
        String content = request.content() == null ? post.getContent() : request.content();
        if (Post.ContentFormat.HTML.name().equals(contentFormat)) {
            content = HtmlSanitizer.sanitize(content);
        }
        if (content == null || content.isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "正文不能为空");
        }
        if (request.title() != null && request.title().length() > 200) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "标题最长 200 个字符");
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        if (request.title() != null) {
            String title = request.title().isBlank() ? null : request.title().trim();
            if (!Objects.equals(title, post.getTitle())) {
                detail.put("title", title);
            }
            post.setTitle(title);
        }
        if (!Objects.equals(content, post.getContent())) {
            detail.put("content", "updated");
            post.setContent(content);
            post.setContentFormat(contentFormat);
        } else if (request.contentFormat() != null && !request.contentFormat().equals(post.getContentFormat())) {
            post.setContentFormat(contentFormat);
            detail.put("contentFormat", contentFormat);
        }
        if (request.coverUrl() != null) {
            post.setCoverUrl(request.coverUrl().isBlank() ? null : request.coverUrl());
            detail.put("coverUrl", post.getCoverUrl());
        }
        if (request.media() != null) {
            List<MediaItem> media = MediaPayloads.normalize(request.media());
            postMediaMapper.delete(Wrappers.<PostMedia>lambdaQuery().eq(PostMedia::getPostId, postId));
            LocalDateTime now = ClockTimes.nowUtc();
            for (int index = 0; index < media.size(); index++) {
                MediaItem item = media.get(index);
                PostMedia postMedia = new PostMedia();
                postMedia.setId(UlidGenerator.generate());
                postMedia.setPostId(postId);
                postMedia.setMediaType(item.type());
                postMedia.setUrl(item.url());
                postMedia.setThumbUrl(item.thumbUrl());
                postMedia.setWidth(item.width());
                postMedia.setHeight(item.height());
                postMedia.setDurationMs(item.durationMs());
                postMedia.setSort(index);
                postMedia.setCreatedAt(now);
                postMediaMapper.insert(postMedia);
            }
            detail.put("mediaCount", media.size());
        }
        if (detail.isEmpty()) {
            return getPostDetail(postId, userId);
        }
        post.setUpdatedAt(ClockTimes.nowUtc());
        postMapper.updateById(post);
        auditLogService.record(post.getChannelId(), userId, ChannelAuditLog.Action.POST_UPDATE, "POST", postId, detail);
        return getPostDetail(postId, userId);
    }

    /**
     * 频道帖子列表：tab=latest|hot|essence，可按板块过滤，游标分页。
     */
    public PageResult<PostResponse> listChannelPosts(String channelId, String tab, String boardId, String cursor,
                                                     Integer limit, String viewerId) {
        accessService.requireVisibleChannel(channelId, viewerId);
        if (boardId != null && !boardId.isBlank()) {
            Board board = boardMapper.selectById(boardId);
            if (board == null || !channelId.equals(board.getChannelId())) {
                throw new AppException(ErrorCode.BOARD_NOT_FOUND);
            }
        }
        int pageSize = CursorUtils.normalizeLimit(limit);
        String tabValue = tab == null || tab.isBlank() ? TAB_LATEST : tab;
        List<Post> rows;
        String nextCursor;
        if (TAB_HOT.equals(tabValue)) {
            CursorUtils.HotCursor hotCursor = CursorUtils.parseHotCursor(cursor);
            rows = postMapper.selectChannelHot(channelId, boardId, false,
                    hotCursor == null ? null : hotCursor.score(),
                    hotCursor == null ? null : hotCursor.id(),
                    pageSize + 1);
            nextCursor = null;
        } else if (TAB_ESSENCE.equals(tabValue)) {
            String cursorId = CursorUtils.validateIdCursor(cursor);
            rows = postMapper.selectChannelLatest(channelId, boardId, true, cursorId, pageSize + 1);
            nextCursor = null;
        } else if (TAB_LATEST.equals(tabValue)) {
            String cursorId = CursorUtils.validateIdCursor(cursor);
            rows = postMapper.selectChannelLatest(channelId, boardId, false, cursorId, pageSize + 1);
            nextCursor = null;
        } else {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "tab 取值必须为 latest、hot 或 essence");
        }

        boolean hasMore = rows.size() > pageSize;
        List<Post> page = hasMore ? rows.subList(0, pageSize) : rows;
        if (!page.isEmpty()) {
            Post last = page.get(page.size() - 1);
            nextCursor = hasMore
                    ? (TAB_HOT.equals(tabValue)
                            ? CursorUtils.formatHotCursor(hotScore(last), last.getId())
                            : last.getId())
                    : null;
        }
        return new PageResult<>(toResponses(page, viewerId), nextCursor);
    }

    /**
     * 帖子详情：按频道可见性校验，浏览量 +1。
     */
    @Transactional
    public PostResponse getPostDetail(String postId, String viewerId) {
        Post post = requirePublishedPost(postId);
        try {
            accessService.requireVisibleChannel(post.getChannelId(), viewerId);
        } catch (AppException ex) {
            throw new AppException(ErrorCode.POST_NOT_FOUND);
        }
        interactionService.incrementViewCount(postId);
        post.setViewCount(post.getViewCount() + 1);
        if (viewerId != null) {
            channelViewService.recordView(post.getChannelId(), postId, viewerId);
        }
        return toResponses(List.of(post), viewerId).get(0);
    }

    /**
     * 加载已发布帖子；不存在/已删除/隐藏统一 404。
     */
    public Post requirePublishedPost(String postId) {
        Post post = postMapper.selectById(postId);
        if (post == null || post.getDeletedAt() != null || !Post.Status.PUBLISHED.name().equals(post.getStatus())) {
            throw new AppException(ErrorCode.POST_NOT_FOUND);
        }
        return post;
    }

    /**
     * 批量组装响应：作者/频道/板块/媒体/点赞状态均批量查询。
     *
     * <p>公开给 feed 模块复用，保证帖子卡片渲染只有一套实现（docs/03 §5.3）。
     */
    public List<PostResponse> toResponses(List<Post> posts, String viewerId) {
        if (posts == null || posts.isEmpty()) {
            return List.of();
        }
        List<String> postIds = posts.stream().map(Post::getId).toList();
        Set<String> authorIds = posts.stream().map(Post::getAuthorId).collect(Collectors.toSet());
        Set<String> channelIds = posts.stream().map(Post::getChannelId).collect(Collectors.toSet());
        Set<String> boardIds = posts.stream().map(Post::getBoardId).collect(Collectors.toSet());

        Map<String, User> authors = userMapper.selectBatchIds(authorIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        Map<String, Channel> channels = channelMapper.selectBatchIds(channelIds).stream()
                .collect(Collectors.toMap(Channel::getId, Function.identity()));
        Map<String, Board> boards = boardMapper.selectBatchIds(boardIds).stream()
                .collect(Collectors.toMap(Board::getId, Function.identity()));
        Map<String, List<PostMedia>> mediaByPost = postMediaMapper.selectList(Wrappers.<PostMedia>lambdaQuery()
                        .in(PostMedia::getPostId, postIds)
                        .orderByAsc(PostMedia::getSort))
                .stream()
                .collect(Collectors.groupingBy(PostMedia::getPostId));
        Set<String> likedPostIds = interactionService.likedPostIds(postIds, viewerId);

        List<PostResponse> responses = new ArrayList<>(posts.size());
        for (Post post : posts) {
            User author = authors.get(post.getAuthorId());
            Channel channel = channels.get(post.getChannelId());
            Board board = boards.get(post.getBoardId());
            List<MediaItem> media = mediaByPost.getOrDefault(post.getId(), Collections.emptyList()).stream()
                    .map(item -> new MediaItem(item.getMediaType(), item.getUrl(), item.getThumbUrl(),
                            item.getWidth(), item.getHeight(), item.getDurationMs()))
                    .toList();
            responses.add(new PostResponse(
                    post.getId(),
                    post.getChannelId(),
                    channel == null ? null : channel.getName(),
                    channel == null ? null : channel.getAvatarUrl(),
                    post.getBoardId(),
                    board == null ? null : board.getName(),
                    post.getAuthorId(),
                    author == null ? null : author.getNickname(),
                    author == null ? null : author.getAvatarUrl(),
                    post.getTitle(),
                    post.getContent(),
                    post.getContentFormat(),
                    post.getCoverUrl(),
                    media,
                    post.getViewCount(),
                    post.getLikeCount(),
                    post.getCommentCount(),
                    post.getShareCount(),
                    likedPostIds.contains(post.getId()),
                    Boolean.TRUE.equals(post.getIsPinned()),
                    Boolean.TRUE.equals(post.getIsEssence()),
                    post.getStatus(),
                    ClockTimes.toInstant(post.getCreatedAt())));
        }
        return responses;
    }

    private long hotScore(Post post) {
        return post.getViewCount() + post.getLikeCount() * 3 + post.getCommentCount() * 5;
    }
}
