package com.isles.search.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.isles.channel.entity.Board;
import com.isles.channel.entity.Channel;
import com.isles.channel.mapper.BoardMapper;
import com.isles.channel.mapper.ChannelMapper;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.id.UlidGenerator;
import com.isles.common.pagination.CursorUtils;
import com.isles.common.pagination.PageResult;
import com.isles.common.security.HtmlSanitizer;
import com.isles.common.time.ClockTimes;
import com.isles.post.entity.Post;
import com.isles.post.mapper.PostMapper;
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
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 搜索服务（docs/03 §5.2 SearchService）：M1 使用 MySQL LIKE 基础检索。
 *
 * <p>安全：
 * <ul>
 *   <li>关键词长度 ≤ 100，LIKE 通配符转义，禁止用户输入进入排序/SQL 结构；</li>
 *   <li>只检索公开频道与公开频道下的已发布帖子（FR-SRCH-09）；</li>
 *   <li>type 只允许 all/channel/post 白名单。</li>
 * </ul>
 * 搜索历史仅本人可见；搜索日志用于 M2 热词聚合。
 */
@Service
@RequiredArgsConstructor
public class SearchService {

    private static final int MAX_KEYWORD_LENGTH = 100;
    private static final int HISTORY_LIMIT = 20;
    private static final int SUMMARY_LENGTH = 120;
    private static final int SUGGEST_LIMIT = 10;

    private final ChannelMapper channelMapper;
    private final PostMapper postMapper;
    private final UserMapper userMapper;
    private final BoardMapper boardMapper;
    private final SearchHistoryMapper searchHistoryMapper;
    private final SearchLogMapper searchLogMapper;

    /**
     * 全站搜索：type=all|channel|post，游标分页（按 ULID 倒序）。
     */
    @Transactional
    public PageResult<SearchItemResponse> search(String q, String type, String cursor, Integer limit, String userId) {
        String keyword = requireKeyword(q);
        String pattern = "%" + escapeLike(keyword) + "%";
        String typeValue = type == null || type.isBlank() ? "all" : type;
        int pageSize = CursorUtils.normalizeLimit(limit);
        String cursorId = CursorUtils.validateIdCursor(cursor);

        List<SearchItemResponse> items;
        String nextCursor = null;
        switch (typeValue) {
            case "channel" -> {
                List<Channel> rows = channelMapper.searchVisible(pattern, cursorId, pageSize + 1);
                boolean hasMore = rows.size() > pageSize;
                List<Channel> page = hasMore ? rows.subList(0, pageSize) : rows;
                items = page.stream().map(this::toChannelItem).toList();
                nextCursor = hasMore && !items.isEmpty() ? items.get(items.size() - 1).id() : null;
            }
            case "post" -> {
                List<Post> rows = postMapper.searchVisiblePosts(pattern, cursorId, pageSize + 1);
                boolean hasMore = rows.size() > pageSize;
                List<Post> page = hasMore ? rows.subList(0, pageSize) : rows;
                Map<String, Channel> channels = loadChannels(page);
                Map<String, User> authors = loadAuthors(page);
                Map<String, Board> boards = loadBoards(page);
                items = page.stream()
                        .map(post -> toPostItem(post, channels.get(post.getChannelId()),
                                authors.get(post.getAuthorId()), boards.get(post.getBoardId())))
                        .toList();
                nextCursor = hasMore && !items.isEmpty() ? items.get(items.size() - 1).id() : null;
            }
            case "all" -> {
                List<Channel> channels = channelMapper.searchVisible(pattern, cursorId, pageSize + 1);
                List<Post> posts = postMapper.searchVisiblePosts(pattern, cursorId, pageSize + 1);
                Map<String, Channel> channelMap = loadChannels(posts);
                Map<String, User> authorMap = loadAuthors(posts);
                Map<String, Board> boardMap = loadBoards(posts);
                List<SearchItemResponse> merged = new ArrayList<>(channels.size() + posts.size());
                channels.forEach(channel -> merged.add(toChannelItem(channel)));
                posts.forEach(post -> merged.add(toPostItem(post, channelMap.get(post.getChannelId()),
                        authorMap.get(post.getAuthorId()), boardMap.get(post.getBoardId()))));
                // 两类资源同样使用 ULID 主键，按 id 倒序合并等价于按时间倒序，游标语义保持一致
                merged.sort(Comparator.comparing(SearchItemResponse::id).reversed());
                boolean hasMore = merged.size() > pageSize;
                items = hasMore ? merged.subList(0, pageSize) : merged;
                nextCursor = hasMore && !items.isEmpty() ? items.get(items.size() - 1).id() : null;
            }
            default -> throw new AppException(ErrorCode.VALIDATION_ERROR, "type 取值必须为 all、channel 或 post");
        }

        recordSearch(keyword, userId, items.size());
        return new PageResult<>(items, nextCursor);
    }

    /**
     * 搜索联想：已加入频道前缀匹配 + 搜索日志高频词前缀匹配。
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
        List<String> keywords = searchLogMapper.selectPopularKeywords(prefixPattern, SUGGEST_LIMIT);
        return new SearchSuggestResponse(channels, keywords);
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

    /**
     * 清空搜索历史。
     */
    @Transactional
    public void clearHistory(String userId) {
        searchHistoryMapper.delete(Wrappers.<SearchHistory>lambdaQuery().eq(SearchHistory::getUserId, userId));
    }

    /**
     * 删除单条历史（防 IDOR：必须属于当前用户）。
     */
    @Transactional
    public void deleteHistory(String historyId, String userId) {
        SearchHistory history = searchHistoryMapper.selectById(historyId);
        if (history == null || !userId.equals(history.getUserId())) {
            throw new AppException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        searchHistoryMapper.deleteById(historyId);
    }

    private void recordSearch(String keyword, String userId, int resultCount) {
        LocalDateTime now = ClockTimes.nowUtc();
        SearchLog log = new SearchLog();
        log.setId(UlidGenerator.generate());
        log.setUserId(userId);
        log.setKeyword(keyword);
        log.setScope(SearchLog.Scope.GLOBAL.name());
        log.setResultCount(resultCount);
        log.setCreatedAt(now);
        searchLogMapper.insert(log);

        if (userId != null) {
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
                ClockTimes.toInstant(channel.getCreatedAt()));
    }

    private SearchItemResponse toPostItem(Post post, Channel channel, User author, Board board) {
        String plain = HtmlSanitizer.toPlainText(post.getContent());
        String summary = plain == null ? "" : truncate(plain, SUMMARY_LENGTH);
        String title = post.getTitle() == null || post.getTitle().isBlank()
                ? truncate(summary, 60)
                : post.getTitle();
        return new SearchItemResponse(
                "POST", post.getId(), title, summary,
                post.getChannelId(), channel == null ? null : channel.getName(),
                channel == null ? null : channel.getAvatarUrl(),
                null, null, post.getLikeCount(), post.getCommentCount(),
                post.getAuthorId(), author == null ? null : author.getUsername(),
                author == null ? null : author.getNickname(),
                author == null ? null : author.getAvatarUrl(),
                post.getBoardId(), board == null ? null : board.getName(),
                ClockTimes.toInstant(post.getCreatedAt()));
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
