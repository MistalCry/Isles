package com.isles.feed.service;

import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.pagination.CursorUtils;
import com.isles.common.pagination.PageResult;
import com.isles.common.time.ClockTimes;
import com.isles.post.dto.PostResponse;
import com.isles.post.entity.Post;
import com.isles.post.mapper.PostMapper;
import com.isles.post.service.PostService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 全站热门讨论（FR-HOT-01/02）：热度 = (浏览 + 点赞×3 + 评论×5) / (小时数 + 2)^1.5。
 *
 * <p>只返回公开频道下的已发布帖子；游标为"热度分 × 1e6 取整 + ULID"，与 SQL 排序表达式一致。
 */
@Service
@RequiredArgsConstructor
public class HotService {

    private static final String RANGE_DAY = "day";
    private static final String RANGE_WEEK = "week";
    private static final String RANGE_MONTH = "month";
    private static final String RANGE_ALL = "all";
    private static final int DEFAULT_RANGE_DAYS = 30;
    private static final int ALL_RANGE_DAYS = 3650;
    private static final double DECAY_EXPONENT = 1.5;
    private static final double SCORE_SCALE = 1_000_000.0;

    private final PostMapper postMapper;
    private final PostService postService;

    /**
     * 热门列表：range=day|week|month|all（默认 month），游标分页。
     */
    public PageResult<PostResponse> hot(String range, String cursor, Integer limit, String viewerId) {
        int pageSize = CursorUtils.normalizeLimit(limit);
        CursorUtils.HotCursor hotCursor = CursorUtils.parseHotCursor(cursor);
        List<Post> rows = postMapper.selectHotGlobal(
                hotCursor == null ? null : hotCursor.score(),
                hotCursor == null ? null : hotCursor.id(),
                resolveSince(range),
                pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<Post> page = hasMore ? rows.subList(0, pageSize) : rows;
        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            Post last = page.get(page.size() - 1);
            nextCursor = CursorUtils.formatHotCursor(scoreMicro(last), last.getId());
        }
        return new PageResult<>(postService.toResponses(page, viewerId), nextCursor);
    }

    /**
     * 与 SQL 排序表达式等价的 Java 实现，用于生成游标。
     */
    public long scoreMicro(Post post) {
        long views = post.getViewCount() == null ? 0 : post.getViewCount();
        long likes = post.getLikeCount() == null ? 0 : post.getLikeCount();
        long comments = post.getCommentCount() == null ? 0 : post.getCommentCount();
        double raw = views + likes * 3.0 + comments * 5.0;
        long hours = 0;
        if (post.getCreatedAt() != null) {
            hours = Math.max(0, Duration.between(post.getCreatedAt(), ClockTimes.nowUtc()).toHours());
        }
        return (long) Math.floor(SCORE_SCALE * raw / Math.pow(hours + 2, DECAY_EXPONENT));
    }

    private LocalDateTime resolveSince(String range) {
        String value = range == null || range.isBlank() ? RANGE_MONTH : range;
        int days = switch (value) {
            case RANGE_DAY -> 1;
            case RANGE_WEEK -> 7;
            case RANGE_MONTH -> DEFAULT_RANGE_DAYS;
            case RANGE_ALL -> ALL_RANGE_DAYS;
            default -> throw new AppException(ErrorCode.VALIDATION_ERROR, "range 取值必须为 day、week、month 或 all");
        };
        return ClockTimes.nowUtc().minusDays(days);
    }
}
