package com.isles.search.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.isles.channel.entity.Board;
import com.isles.channel.entity.Channel;
import com.isles.channel.mapper.BoardMapper;
import com.isles.channel.mapper.ChannelMapper;
import com.isles.channel.service.ChannelAccessService;
import com.isles.common.dto.MediaItem;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.id.UlidGenerator;
import com.isles.common.pagination.CursorUtils;
import com.isles.common.pagination.PageResult;
import com.isles.common.security.HtmlSanitizer;
import com.isles.common.time.ClockTimes;
import com.isles.post.dto.PostResponse;
import com.isles.post.entity.Post;
import com.isles.post.entity.PostMedia;
import com.isles.post.mapper.PostMapper;
import com.isles.post.mapper.PostMediaMapper;
import com.isles.post.service.PostService;
import com.isles.search.dto.HotKeywordResponse;
import com.isles.search.dto.SearchHistoryResponse;
import com.isles.search.dto.SearchItemResponse;
import com.isles.search.dto.SearchSuggestResponse;
import com.isles.search.entity.SearchHistory;
import com.isles.search.entity.SearchLog;
import com.isles.search.mapper.SearchHistoryMapper;
import com.isles.search.mapper.SearchLogMapper;
import com.isles.user.entity.User;
import com.isles.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 搜索服务（docs/03 §5.2 SearchService、§4.10）：MySQL LIKE 基础检索 + 排序筛选 + 频道内搜索 + 热词联想。
 *
 * <p>安全：关键词长度 ≤ 100、LIKE 通配符转义、排序白名单（禁止用户输入进入 ORDER BY）、
 * 只检索公开频道/公开帖子（FR-SRCH-09）。
 *
 * <p>排序：
 * <ul>
 *   <li>频道：relevance（名称命中优先）/ members（人数）/ active（帖子数）/ latest（最新）；</li>
 *   <li>帖子：relevance（标题命中优先）/ latest。</li>
 * </ul>
 * 非 latest 排序使用偏移游标（{@code o:N}），latest 使用 ULID 游标。
 */
@Service
@RequiredArgsConstructor
public class SearchService {

    private static final int MAX_KEYWORD_LENGTH = 100;
    private static final int HISTORY_LIMIT = 20;
    private static final int SUMMARY_LENGTH = 120;
    private static final int SUGGEST_LIMIT = 10;

    private static final String SORT_RELEVANCE = "relevance";
    private static final String SORT_MEMBERS = "members";
    private static final String SORT_ACTIVE = "active";
    private static final String SORT_LATEST = "latest";

    private final ChannelMapper channelMapper;
    private final PostMapper postMapper;
    private final PostMediaMapper postMediaMapper;
    private final UserMapper userMapper;
    private final BoardMapper boardMapper;
    private final SearchHistoryMapper searchHistoryMapper;
    private final SearchLogMapper searchLogMapper;
    private final SearchHotwordService searchHotwordService;
    private final ChannelAccessService accessService;
    private final PostService postService;

    /**
     * 全站搜索：type=all|channel|post，sort 见类注释。
     */
    @Transactional
    public PageResult<SearchItemResponse> search(String q, String type, String sort, String cursor, Integer limit,
                                                 String userId) {
        String keyword = requireKeyword(q);
        String pattern = "%" + escapeLike(keyword) + "%";
        String typeValue = type == null || type.isBlank() ? "all" : type;
        String sortValue = sort == null || sort.isBlank() ? SORT_RELEVANCE : sort;
        int pageSize = CursorUtils.normalizeLimit(limit);

        List<SearchItemResponse> items;
        String nextCursor = null;
        switch (typeValue) {
            case "channel" -> {
                ChannelPage channelPage = searchChannels(pattern, sortValue, cursor, pageSize);
                items = channelPage.items().stream().map(this::toChannelItem).toList();
                nextCursor = channelPage.nextCursor();
            }
            case "post" -> {
                PostPage postPage = searchPosts(pattern, sortValue, cursor, pageSize);
                items = toPostItems(postPage.items());
                nextCursor = postPage.nextCursor();
            }
            case "all" -> {
                // 综合搜索按最新合并（相关度排序对混合类型没有统一口径，前端可用 channel/post tab 精确排序）
                String cursorId = CursorUtils.validateIdCursor(cursor);
                List<Channel> channels = channelMapper.searchVisible(pattern, cursorId, pageSize + 1);
                List<Post> posts = postMapper.searchVisiblePosts(pattern, cursorId, pageSize + 1);
                List<SearchItemResponse> merged = new ArrayList<>(channels.size() + posts.size());
                channels.forEach(channel -> merged.add(toChannelItem(channel)));
                merged.addAll(toPostItems(posts));
                merged.sort(Comparator.comparing(SearchItemResponse::id).reversed());
                boolean hasMore = merged.size() > pageSize;
                items = hasMore ? merged.subList(0, pageSize) : merged;
                nextCursor = hasMore && !items.isEmpty() ? items.get(items.size() - 1).id() : null;
            }
            default -> throw new AppException(ErrorCode.VALIDATION_ERROR, "type 取值必须为 all、channel 或 post");
        }

        recordSearch(keyword, userId, SearchLog.Scope.GLOBAL, null, items.size());
        return new PageResult<>(items, nextCursor);
    }

    private record ChannelPage(List<Channel> items, String nextCursor) {
    }

    private record PostPage(List<Post> items, String nextCursor) {
    }

    /**
     * 频道结果排序：relevance/members/active 用偏移游标，latest 用 ULID 游标。
     */
    private ChannelPage searchChannels(String pattern, String sort, String cursor, int pageSize) {
        if (SORT_LATEST.equals(sort)) {
            String cursorId = CursorUtils.validateIdCursor(cursor);
            List<Channel> rows = channelMapper.searchVisible(pattern, cursorId, pageSize + 1);
            boolean hasMore = rows.size() > pageSize;
            List<Channel> page = hasMore ? rows.subList(0, pageSize) : rows;
            String nextCursor = hasMore && !page.isEmpty() ? page.get(page.size() - 1).getId() : null;
            return new ChannelPage(page, nextCursor);
        }
        int offset = CursorUtils.parseOffsetCursor(cursor);
        List<Channel> rows = switch (sort) {
            case SORT_MEMBERS -> channelMapper.searchVisibleByMembers(pattern, offset, pageSize + 1);
            case SORT_ACTIVE -> channelMapper.searchVisibleByActive(pattern, offset, pageSize + 1);
            case SORT_RELEVANCE -> channelMapper.searchVisibleByRelevance(pattern, offset, pageSize + 1);
            default -> throw new AppException(ErrorCode.VALIDATION_ERROR,
                    "频道排序取值必须为 relevance、members、active 或 latest");
        };
        boolean hasMore = rows.size() > pageSize;
        List<Channel> page = hasMore ? rows.subList(0, pageSize) : rows;
        String nextCursor = hasMore ? CursorUtils.formatOffsetCursor(offset + pageSize) : null;
        return new ChannelPage(page, nextCursor);
    }

    /**
     * 帖子结果排序：relevance 偏移游标，latest ULID 游标。
     */
    private PostPage searchPosts(String pattern, String sort, String cursor, int pageSize) {
        if (SORT_LATEST.equals(sort)) {
            String cursorId = CursorUtils.validateIdCursor(cursor);
            List<Post> rows = postMapper.searchVisiblePosts(pattern, cursorId, pageSize + 1);
            boolean hasMore = rows.size() > pageSize;
            List<Post> page = hasMore ? rows.subList(0, pageSize) : rows;
            String nextCursor = hasMore && !page.isEmpty() ? page.get(page.size() - 1).getId() : null;
            return new PostPage(page, nextCursor);
        }
        if (!SORT_RELEVANCE.equals(sort)) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "帖子排序取值必须为 relevance 或 latest");
        }
        int offset = CursorUtils.parseOffsetCursor(cursor);
        List<Post> rows = postMapper.searchVisiblePostsByRelevance(pattern, offset, pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<Post> page = hasMore ? rows.subList(0, pageSize) : rows;
        String nextCursor = hasMore ? CursorUtils.formatOffsetCursor(offset + pageSize) : null;
        return new PostPage(page, nextCursor);
    }

    /**
     * 频道内搜索（FR-SRCH-08）：可见性校验后仅检索该频道帖子，结果复用帖子卡片结构。
     */
    @Transactional
    public PageResult<PostResponse> channelSearch(String channelId, String q, String boardId, String sort,
                                                  String cursor, Integer limit, String viewerId) {
        accessService.requireVisibleChannel(channelId, viewerId);
        if (boardId != null && !boardId.isBlank()) {
            Board board = boardMapper.selectById(boardId);
            if (board == null || board.getDeletedAt() != null || !channelId.equals(board.getChannelId())) {
                throw new AppException(ErrorCode.BOARD_NOT_FOUND);
            }
        }
        String keyword = requireKeyword(q);
        String pattern = "%" + escapeLike(keyword) + "%";
        int pageSize = CursorUtils.normalizeLimit(limit);
        int offset = CursorUtils.parseOffsetCursor(cursor);
        String sortValue = sort == null || sort.isBlank() ? SORT_RELEVANCE : sort;
        List<Post> rows = switch (sortValue) {
            case SORT_RELEVANCE -> postMapper.searchInChannelByRelevance(channelId, boardId, pattern, offset,
                    pageSize + 1);
            case SORT_LATEST -> postMapper.searchInChannelLatest(channelId, boardId, pattern, offset, pageSize + 1);
            default -> throw new AppException(ErrorCode.VALIDATION_ERROR,
                    "频道内搜索排序取值必须为 relevance 或 latest");
        };
        boolean hasMore = rows.size() > pageSize;
        List<Post> page = hasMore ? rows.subList(0, pageSize) : rows;
        String nextCursor = hasMore ? CursorUtils.formatOffsetCursor(offset + pageSize) : null;
        recordSearch(keyword, viewerId, SearchLog.Scope.CHANNEL, channelId, page.size());
        return new PageResult<>(postService.toResponses(page, viewerId), nextCursor);
    }

    /**
     * 搜索联想：已加入频道前缀匹配 + 热词前缀匹配（热词来自 hot_keywords 聚合）。
     */
    public SearchSuggestResponse suggest(String q, String userId) {
        String keyword = requireKeyword(q);
        String lower = keyword.toLowerCase();
        List<SearchItemResponse> channels = channelMapper.selectJoinedChannels(userId).stream()
                .filter(channel -> channel.getName() != null && channel.getName().toLowerCase().startsWith(lower))
                .limit(SUGGEST_LIMIT)
                .map(this::toChannelItem)
                .toList();
        String prefixPattern = escapeLike(keyword) + "%";
        List<String> keywords = searchHotwordService.keywordsByPrefix(prefixPattern, SUGGEST_LIMIT);
        return new SearchSuggestResponse(channels, keywords);
    }

    /**
     * 搜索热词榜（公开只读）。
     */
    public PageResult<HotKeywordResponse> hotKeywords(Integer limit) {
        int size = limit == null || limit <= 0 ? 10 : Math.min(limit, 50);
        return new PageResult<>(searchHotwordService.top(size), null);
    }

    /**
     * 搜索历史（最近 20 条，仅本人）。
     */
    public List<SearchHistoryResponse> history(String userId) {
        return searchHistoryMapper.selectRecent(userId, HISTORY_LIMIT).stream()
                .map(row -> new SearchHistoryResponse(row.getId(), row.getKeyword(),
                        ClockTimes.toInstant(row.getCreatedAt())))
                .toList();
    }

    @Transactional
    public void clearHistory(String userId) {
        searchHistoryMapper.delete(Wrappers.<SearchHistory>lambdaQuery().eq(SearchHistory::getUserId, userId));
    }

    @Transactional
    public void deleteHistory(String historyId, String userId) {
        SearchHistory history = searchHistoryMapper.selectById(historyId);
        if (history == null || !userId.equals(history.getUserId())) {
            throw new AppException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        searchHistoryMapper.deleteById(historyId);
    }

    private void recordSearch(String keyword, String userId, SearchLog.Scope scope, String channelId,
                              int resultCount) {
        LocalDateTime now = ClockTimes.nowUtc();
        SearchLog log = new SearchLog();
        log.setId(UlidGenerator.generate());
        log.setUserId(userId);
        log.setKeyword(keyword);
        log.setScope(scope.name());
        log.setChannelId(channelId);
        log.setResultCount(resultCount);
        log.setCreatedAt(now);
        searchLogMapper.insert(log);

        if (userId != null && scope == SearchLog.Scope.GLOBAL) {
            // 同一关键词只保留最新一条（唯一约束 uk_search_history_user_keyword）
            searchHistoryMapper.delete(Wrappers.<SearchHistory>lambdaQuery()
                    .eq(SearchHistory::getUserId, userId)
                    .eq(SearchHistory::getKeyword, keyword));
            SearchHistory history = new SearchHistory();
            history.setId(UlidGenerator.generate());
            history.setUserId(userId);
            history.setKeyword(keyword);
            history.setCreatedAt(now);
            searchHistoryMapper.insert(history);
        }
    }

    private Map<String, Channel> loadChannels(List<Post> posts) {
        List<String> channelIds = posts.stream().map(Post::getChannelId).distinct().toList();
        if (channelIds.isEmpty()) {
            return Map.of();
        }
        return channelMapper.selectBatchIds(channelIds).stream()
                .collect(Collectors.toMap(Channel::getId, Function.identity()));
    }

    private Map<String, User> loadAuthors(List<Post> posts) {
        List<String> authorIds = posts.stream().map(Post::getAuthorId).filter(id -> id != null).distinct().toList();
        if (authorIds.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(authorIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }

    private Map<String, Board> loadBoards(List<Post> posts) {
        List<String> boardIds = posts.stream().map(Post::getBoardId).filter(id -> id != null).distinct().toList();
        if (boardIds.isEmpty()) {
            return Map.of();
        }
        return boardMapper.selectBatchIds(boardIds).stream()
                .collect(Collectors.toMap(Board::getId, Function.identity()));
    }

    private SearchItemResponse toChannelItem(Channel channel) {
        return new SearchItemResponse(
                "CHANNEL", channel.getId(), channel.getName(), channel.getDescription(),
                channel.getId(), channel.getName(), channel.getAvatarUrl(), channel.getAvatarUrl(),
                channel.getMemberCount(), null, null, null, null, null, null, null, null,
                ClockTimes.toInstant(channel.getCreatedAt()),
                // 频道没有帖子封面语义：用频道封面/头像给前端做背景图
                channel.getCoverUrl(), List.of(),
                // 频道结果不是内容，AI 标识一律 false（代理账号建频道属 P3，本轮不做）
                false, false);
    }

    /**
     * 批量组装帖子搜索结果：频道/作者/板块/首图各查一次，避免逐帖查询（N+1）。
     */
    private List<SearchItemResponse> toPostItems(List<Post> posts) {
        if (posts == null || posts.isEmpty()) {
            return List.of();
        }
        SearchContext context = loadContext(posts);
        List<SearchItemResponse> items = new ArrayList<>(posts.size());
        for (Post post : posts) {
            items.add(toPostItem(post, context));
        }
        return items;
    }

    private SearchContext loadContext(List<Post> posts) {
        Map<String, PostMedia> firstMedia = loadFirstMedia(posts);
        return new SearchContext(loadChannels(posts), loadAuthors(posts), loadBoards(posts), firstMedia);
    }

    /**
     * 每个帖子的首图（post_media 中 sort 最小的一张）：一次 IN 查询后按 postId 取第一条。
     */
    private Map<String, PostMedia> loadFirstMedia(List<Post> posts) {
        List<String> postIds = posts.stream().map(Post::getId).filter(id -> id != null).distinct().toList();
        if (postIds.isEmpty()) {
            return Map.of();
        }
        Map<String, PostMedia> first = new HashMap<>();
        for (PostMedia media : postMediaMapper.selectByPostIds(postIds)) {
            // 已按 sort 升序返回，首次出现的即为该帖首图
            first.putIfAbsent(media.getPostId(), media);
        }
        return first;
    }

    private SearchItemResponse toPostItem(Post post, SearchContext context) {
        Channel channel = context.channels().get(post.getChannelId());
        User author = context.authors().get(post.getAuthorId());
        Board board = context.boards().get(post.getBoardId());
        PostMedia firstMedia = context.firstMedia().get(post.getId());
        String plain = HtmlSanitizer.toPlainText(post.getContent());
        String summary = plain == null ? "" : truncate(plain, SUMMARY_LENGTH);
        String title = post.getTitle() == null || post.getTitle().isBlank()
                ? truncate(summary, 60)
                : post.getTitle();
        List<MediaItem> media = firstMedia == null
                ? List.of()
                : List.of(new MediaItem(firstMedia.getMediaType(), firstMedia.getUrl(), firstMedia.getThumbUrl(),
                        firstMedia.getWidth(), firstMedia.getHeight(), firstMedia.getDurationMs()));
        return new SearchItemResponse(
                "POST", post.getId(), title, summary,
                post.getChannelId(), channel == null ? null : channel.getName(),
                channel == null ? null : channel.getAvatarUrl(),
                null, null, post.getLikeCount(), post.getCommentCount(),
                post.getAuthorId(), author == null ? null : author.getUsername(),
                author == null ? null : author.getNickname(),
                author == null ? null : author.getAvatarUrl(),
                post.getBoardId(), board == null ? null : board.getName(),
                ClockTimes.toInstant(post.getCreatedAt()),
                post.getCoverUrl(), media,
                Boolean.TRUE.equals(post.getIsAiGenerated()),
                author != null && Boolean.TRUE.equals(author.getIsAgent()));
    }

    /** 搜索结果一次查询出来的关联数据。 */
    private record SearchContext(Map<String, Channel> channels,
                                 Map<String, User> authors,
                                 Map<String, Board> boards,
                                 Map<String, PostMedia> firstMedia) {
    }

    private String requireKeyword(String q) {
        if (q == null || q.isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "搜索关键词不能为空");
        }
        String keyword = q.trim();
        if (keyword.length() > MAX_KEYWORD_LENGTH) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "搜索关键词最长 100 个字符");
        }
        return keyword;
    }

    /**
     * 转义 MySQL LIKE 通配符（默认转义符为反斜杠），防止用户输入 % / _ 造成全表扫描。
     */
    private String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
