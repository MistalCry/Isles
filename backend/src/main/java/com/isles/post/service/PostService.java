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
import com.isles.comment.entity.Comment;
import com.isles.comment.mapper.CommentMapper;
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
import com.isles.post.dto.UserPostPage;
import com.isles.post.dto.TopCommentResponse;
import com.isles.post.entity.Post;
import com.isles.post.entity.PostMedia;
import com.isles.post.mapper.PostMapper;
import com.isles.post.mapper.PostMediaMapper;
import com.isles.user.entity.User;
import com.isles.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
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

    /** 「我的帖子」默认/最大分页。 */
    private static final int DEFAULT_MY_POSTS_SIZE = 20;
    private static final int MAX_MY_POSTS_SIZE = 50;

    /** 浏览量去重窗口：同一会话 30 分钟内重复访问只计一次。 */
    private static final Duration VIEW_DEDUPE_WINDOW = Duration.ofMinutes(30);

    private static final String TAB_LATEST = "latest";
    private static final String TAB_HOT = "hot";
    private static final String TAB_ESSENCE = "essence";
    /** 置顶帖上限：与前端置顶区展示条数一致（docs/03 FR-POST-05）。 */
    private static final int MAX_PINNED = 20;

    /** 进程内浏览去重（多实例场景见类注释：各实例独立计数）。 */
    private final PostViewDeduper viewDeduper = new PostViewDeduper(VIEW_DEDUPE_WINDOW);

    private final PostMapper postMapper;
    private final PostMediaMapper postMediaMapper;
    private final CommentMapper commentMapper;
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
        // 敏感词：按配置模式处理（REVIEW 只记录 / MASK 打码后入库 / BLOCK 直接 422）
        com.isles.platform.wordfilter.SensitiveWordService.Inspection inspection = sensitiveWordService.inspect(content,
                Post.ContentFormat.HTML.name().equals(contentFormat));
        content = inspection.text();
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
        // AI 标识（V13）：代理账号产出的内容必须可识别，前台据此展示徽章
        post.setIsAiGenerated(isAgentAccount(userId));
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
        // 敏感词命中记录（内容已按模式处理：打码或已放行）
        sensitiveWordService.recordHits("POST", post.getId(), userId, inspection);
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
     * 我的帖子（docs/05 §2.1）：按发布时间倒序，支持按频道过滤，page/size 偏移分页。
     *
     * <p>与频道帖子流同构（{@link PostResponse}，含 {@code topComment}），便于前端复用帖子卡片。
     */
    public UserPostPage listMyPosts(String userId, String channelId, Integer page, Integer size) {
        int pageNo = page == null || page <= 0 ? 1 : page;
        int pageSize = size == null || size <= 0 ? DEFAULT_MY_POSTS_SIZE : Math.min(size, MAX_MY_POSTS_SIZE);
        String channelFilter = channelId == null || channelId.isBlank() ? null : channelId.trim();
        long total = postMapper.countUserPosts(userId, channelFilter);
        List<Post> rows = postMapper.selectUserPosts(userId, channelFilter, pageSize, (pageNo - 1) * pageSize);
        return new UserPostPage(toResponses(rows, userId), total, pageNo, pageSize);
    }

    /**
     * 频道置顶帖列表（docs/03 FR-POST-05）：按置顶时间倒序，最多 20 条。
     *
     * <p>频道帖子流（latest/essence）已排除置顶帖，本接口是置顶帖的唯一入口。
     */
    public PageResult<PostResponse> listPinnedPosts(String channelId, Integer limit, String viewerId) {
        accessService.requireVisibleChannel(channelId, viewerId);
        int pageSize = limit == null || limit <= 0 ? MAX_PINNED : Math.min(limit, MAX_PINNED);
        List<Post> rows = postMapper.selectChannelPinned(channelId, null, pageSize);
        return new PageResult<>(toResponses(rows, viewerId), null);
    }

    /**
     * 帖子详情：按频道可见性校验，浏览量按「有效浏览」计数。
     *
     * <p>计数规则（docs/03 FR-POST-05、docs/11 §5.4）：
     * <ul>
     *   <li>作者本人查看不计（避免自己刷自己）；</li>
     *   <li>同一会话 30 分钟内重复访问同一帖子只计一次（F5/来回点不叠加）；</li>
     *   <li>匿名访问用「IP + UA」作为会话标识（无请求上下文时不参与去重）。</li>
     * </ul>
     */
    @Transactional
    public PostResponse getPostDetail(String postId, String viewerId) {
        Post post = requirePublishedPost(postId);
        try {
            accessService.requireVisibleChannel(post.getChannelId(), viewerId);
        } catch (AppException ex) {
            throw new AppException(ErrorCode.POST_NOT_FOUND);
        }
        boolean selfView = viewerId != null && viewerId.equals(post.getAuthorId());
        String viewKey = selfView ? null : viewKey(postId, viewerId);
        if (!selfView && viewDeduper.shouldCount(viewKey, System.currentTimeMillis())) {
            interactionService.incrementViewCount(postId);
            post.setViewCount(post.getViewCount() + 1);
            if (viewerId != null) {
                channelViewService.recordView(post.getChannelId(), postId, viewerId);
            }
        }
        return toResponses(List.of(post), viewerId).get(0);
    }

    /**
     * 浏览去重键：登录用户按 userId，匿名按 IP + UA（拿不到请求上下文时返回 null → 不做去重）。
     */
    private String viewKey(String postId, String viewerId) {
        if (viewerId != null) {
            return postId + "|u:" + viewerId;
        }
        String fingerprint = clientFingerprint();
        return fingerprint == null ? null : postId + "|a:" + fingerprint;
    }

    private String clientFingerprint() {
        try {
            var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet) {
                var request = servlet.getRequest();
                String ip = request.getRemoteAddr();
                if (ip == null || ip.isBlank()) {
                    return null;
                }
                String userAgent = request.getHeader("User-Agent");
                return ip + "|" + (userAgent == null ? "" : Integer.toHexString(userAgent.hashCode()));
            }
        } catch (RuntimeException ex) {
            // 非 Web 线程（定时任务/测试）
        }
        return null;
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

        // 每帖 top1 高赞评论：一次窗口函数查询取全，避免逐帖查询（N+1）
        Map<String, Comment> topComments = commentMapper.selectTopLikedByPostIds(postIds).stream()
                .collect(Collectors.toMap(Comment::getPostId, Function.identity(), (left, right) -> left));
        topComments.values().stream().map(Comment::getAuthorId).forEach(authorIds::add);
        Set<String> likedTopCommentIds = topComments.isEmpty()
                ? Set.of()
                : interactionService.likedCommentIds(
                        topComments.values().stream().map(Comment::getId).toList(), viewerId);

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
                    ClockTimes.toInstant(post.getCreatedAt()),
                    toTopComment(topComments.get(post.getId()), authors, likedTopCommentIds),
                    // AI 标识：内容级标记 + 作者级标记（作者已在上面批量加载，不额外查库）
                    Boolean.TRUE.equals(post.getIsAiGenerated()),
                    author != null && Boolean.TRUE.equals(author.getIsAgent())));
        }
        return responses;
    }

    private TopCommentResponse toTopComment(Comment comment, Map<String, User> authors,
                                            Set<String> likedCommentIds) {
        if (comment == null) {
            return null;
        }
        User author = authors.get(comment.getAuthorId());
        return new TopCommentResponse(
                comment.getId(),
                comment.getPostId(),
                comment.getAuthorId(),
                author == null ? null : author.getNickname(),
                author == null ? null : author.getAvatarUrl(),
                comment.getContent(),
                comment.getLikeCount() == null ? 0L : comment.getLikeCount(),
                likedCommentIds.contains(comment.getId()),
                ClockTimes.toInstant(comment.getCreatedAt()));
    }

    private long hotScore(Post post) {
        return post.getViewCount() + post.getLikeCount() * 3 + post.getCommentCount() * 5;
    }

    /**
     * 作者是否为 AI 代理账号（V13）：创建内容时查一次主键，成本可忽略；
     * 判定失败一律按「非代理」处理（宁可少打标也不误标真人）。
     */
    private boolean isAgentAccount(String userId) {
        if (userId == null) {
            return false;
        }
        com.isles.user.entity.User author = userMapper.selectById(userId);
        return author != null && Boolean.TRUE.equals(author.getIsAgent());
    }
}
