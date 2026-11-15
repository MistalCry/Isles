package com.isles.analytics.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.isles.analytics.dto.BoardStatResponse;
import com.isles.analytics.dto.ContentStatsResponse;
import com.isles.analytics.dto.DailyStatsResponse;
import com.isles.analytics.dto.FunnelStatsResponse;
import com.isles.analytics.dto.MemberStatsResponse;
import com.isles.analytics.dto.RoleDistributionResponse;
import com.isles.analytics.dto.StatsOverviewResponse;
import com.isles.analytics.dto.TopPostStatResponse;
import com.isles.analytics.dto.TrendPointResponse;
import com.isles.analytics.entity.DailyChannelStats;
import com.isles.channel.entity.Role;
import com.isles.channel.mapper.ChannelMapper;
import com.isles.channel.mapper.ChannelMemberMapper;
import com.isles.channel.mapper.ChannelStatsMapper;
import com.isles.channel.mapper.ChannelViewLogMapper;
import com.isles.channel.mapper.DailyChannelStatsMapper;
import com.isles.channel.mapper.RoleMapper;
import com.isles.channel.mapper.row.RoleMemberCountRow;
import com.isles.channel.service.ChannelAccessService;
import com.isles.channel.service.ChannelPermissions;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.id.UlidGenerator;
import com.isles.common.security.HtmlSanitizer;
import com.isles.common.time.ClockTimes;
import com.isles.post.entity.Post;
import com.isles.post.mapper.PostMapper;
import com.isles.post.mapper.row.BoardPostCountRow;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 数据中心（docs/03 §8）：日聚合 + 概览/趋势/内容/成员分析。
 *
 * <p>口径：按 Asia/Shanghai 自然日切分，数据库存 UTC；聚合结果按 (channel_id, stat_date) upsert，
 * 可重复执行不重复计数（docs/03 §8.3）。UV/PV/活跃数据源为 channel_view_logs 与业务明细表；
 * 匿名浏览不参与统计（单机无 Redis HyperLogLog 的等价实现，限制见交付报告）。
 */
@Service
@RequiredArgsConstructor
public class StatsService {

    private static final Logger log = LoggerFactory.getLogger(StatsService.class);
    private static final ZoneId STAT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int DEFAULT_TREND_DAYS = 7;
    private static final int MAX_TREND_DAYS = 90;
    private static final int CONTENT_ANALYSIS_DAYS = 30;
    private static final int TOP_POST_LIMIT = 10;

    private final ChannelStatsMapper channelStatsMapper;
    private final ChannelViewLogMapper channelViewLogMapper;
    private final DailyChannelStatsMapper dailyChannelStatsMapper;
    private final ChannelMapper channelMapper;
    private final ChannelMemberMapper channelMemberMapper;
    private final RoleMapper roleMapper;
    private final PostMapper postMapper;
    private final ChannelAccessService accessService;

    /**
     * 每日 00:10 聚合昨日数据（任务幂等，可重复执行）。
     */
    @Scheduled(cron = "${app.stats.aggregate-cron:0 10 0 * * ?}")
    public void scheduledAggregateYesterday() {
        LocalDate yesterday = LocalDate.now(STAT_ZONE).minusDays(1);
        aggregateAll(yesterday);
    }

    /**
     * 聚合全部活跃频道的指定日期（手动触发/补跑）。
     */
    public int aggregateAll(LocalDate date) {
        int success = 0;
        for (String channelId : channelMapper.selectActiveChannelIds()) {
            try {
                aggregate(channelId, date);
                success++;
            } catch (RuntimeException ex) {
                log.error("频道统计聚合失败 channelId={} date={}", channelId, date, ex);
            }
        }
        log.info("频道统计聚合完成 date={} success={}", date, success);
        return success;
    }

    /**
     * 聚合单个频道指定自然日：所有指标由明细表按 UTC 时间窗统计，再 upsert。
     */
    @Transactional
    public DailyStatsResponse aggregate(String channelId, LocalDate date) {
        if (channelId == null || date == null) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "频道与统计日期不能为空");
        }
        LocalDateTime from = date.atStartOfDay(STAT_ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime to = date.plusDays(1).atStartOfDay(STAT_ZONE).withZoneSameInstant(ZoneOffset.UTC)
                .toLocalDateTime();

        long memberCount = value(channelStatsMapper.countActiveMembers(channelId));
        long newMembers = value(channelStatsMapper.countMembersJoinedBetween(channelId, from, to));
        long lostMembers = value(channelStatsMapper.countMembersLeftBetween(channelId, from, to));
        long postViews = value(channelViewLogMapper.countViews(channelId, from, to));
        long visitUv = value(channelViewLogMapper.countDistinctViewers(channelId, from, to));
        long activeUsers = channelViewLogMapper.selectActiveUserIds(channelId, from, to).size();
        long posts = value(channelStatsMapper.countPostsBetween(channelId, from, to));
        long comments = value(channelStatsMapper.countCommentsBetween(channelId, from, to));
        long postLikes = value(channelStatsMapper.countPostLikesBetween(channelId, from, to));
        long commentLikes = value(channelStatsMapper.countCommentLikesBetween(channelId, from, to));
        long likes = postLikes + commentLikes;
        long shares = value(channelStatsMapper.countSharesBetween(channelId, from, to));
        long interactions = likes + comments + shares;

        DailyChannelStats existing = dailyChannelStatsMapper.selectByChannelAndDate(channelId, date);
        DailyChannelStats stats = existing == null ? new DailyChannelStats() : existing;
        if (existing == null) {
            stats.setId(UlidGenerator.generate());
            stats.setChannelId(channelId);
            stats.setStatDate(date);
            stats.setCreatedAt(ClockTimes.nowUtc());
        }
        stats.setMemberCount(memberCount);
        stats.setNewMembers(newMembers);
        stats.setLostMembers(lostMembers);
        stats.setVisitUv(visitUv);
        stats.setPostViews(postViews);
        stats.setActiveUsers(activeUsers);
        stats.setActiveRate(rate(activeUsers, memberCount));
        stats.setPosts(posts);
        stats.setComments(comments);
        stats.setLikes(likes);
        stats.setShares(shares);
        stats.setInteractions(interactions);
        stats.setInteractionRate(rate(interactions, postViews));
        stats.setUpdatedAt(ClockTimes.nowUtc());
        dailyChannelStatsMapper.upsert(stats);
        return toResponse(stats);
    }

    /**
     * 概览：实时成员/帖子数 + 昨日指标 + 与前一日的差值。
     */
    public StatsOverviewResponse overview(String channelId, String actorId) {
        accessService.requirePermission(channelId, actorId, ChannelPermissions.DATA_VIEW);
        LocalDate yesterday = LocalDate.now(STAT_ZONE).minusDays(1);
        DailyStatsResponse yesterdayStats = ensureAggregated(channelId, yesterday);
        DailyStatsResponse dayBeforeStats = ensureAggregated(channelId, yesterday.minusDays(1));
        long memberCount = value(channelStatsMapper.countActiveMembers(channelId));
        Long postCountValue = postMapper.selectCount(Wrappers.<Post>lambdaQuery()
                .eq(Post::getChannelId, channelId)
                .eq(Post::getStatus, Post.Status.PUBLISHED.name())
                .isNull(Post::getDeletedAt));
        long postCount = postCountValue == null ? 0 : postCountValue;
        long memberDelta = yesterdayStats.newMembers() - yesterdayStats.lostMembers();
        long postDelta = yesterdayStats.posts() - dayBeforeStats.posts();
        return new StatsOverviewResponse(channelId, memberCount, postCount, yesterdayStats, memberDelta, postDelta);
    }

    /**
     * 趋势：最近 N 天（默认 7，上限 90），缺失日期按 0 补齐。
     */
    public List<DailyStatsResponse> trends(String channelId, Integer days, String actorId) {
        accessService.requirePermission(channelId, actorId, ChannelPermissions.DATA_VIEW);
        int window = normalizeDays(days);
        LocalDate end = LocalDate.now(STAT_ZONE).minusDays(1);
        LocalDate start = end.minusDays(window - 1L);
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            ensureAggregated(channelId, date);
        }
        Map<LocalDate, DailyStatsResponse> existing = dailyChannelStatsMapper.selectRange(channelId, start, end)
                .stream()
                .collect(Collectors.toMap(DailyChannelStats::getStatDate, this::toResponse));
        List<DailyStatsResponse> points = new ArrayList<>(window);
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            points.add(existing.getOrDefault(date, empty(date)));
        }
        return points;
    }

    /**
     * 内容分析：板块分布、Top 帖子、互动漏斗（最近 30 天聚合）。
     */
    public ContentStatsResponse contentStats(String channelId, String actorId) {
        accessService.requirePermission(channelId, actorId, ChannelPermissions.DATA_VIEW);
        List<BoardStatResponse> boards = postMapper.countPostsByBoard(channelId).stream()
                .map(this::toBoardStat)
                .toList();
        List<TopPostStatResponse> topPosts = postMapper.selectTopPosts(channelId, TOP_POST_LIMIT).stream()
                .map(this::toTopPost)
                .toList();

        LocalDate end = LocalDate.now(STAT_ZONE).minusDays(1);
        LocalDate start = end.minusDays(CONTENT_ANALYSIS_DAYS - 1L);
        long views = 0;
        long likes = 0;
        long comments = 0;
        long shares = 0;
        for (DailyChannelStats stats : dailyChannelStatsMapper.selectRange(channelId, start, end)) {
            views += stats.getPostViews() == null ? 0 : stats.getPostViews();
            likes += stats.getLikes() == null ? 0 : stats.getLikes();
            comments += stats.getComments() == null ? 0 : stats.getComments();
            shares += stats.getShares() == null ? 0 : stats.getShares();
        }
        long interactions = likes + comments + shares;
        FunnelStatsResponse funnel = new FunnelStatsResponse(views, likes, comments, shares,
                rate(interactions, views));
        return new ContentStatsResponse(boards, topPosts, funnel);
    }

    /**
     * 成员分析：成员总数、身份组分布、新增成员趋势。
     */
    public MemberStatsResponse memberStats(String channelId, Integer days, String actorId) {
        accessService.requirePermission(channelId, actorId, ChannelPermissions.DATA_VIEW);
        int window = normalizeDays(days);
        long memberCount = value(channelStatsMapper.countActiveMembers(channelId));
        List<RoleMemberCountRow> rows = channelMemberMapper.selectRoleMemberCounts(channelId);
        List<String> roleIds = rows.stream().map(RoleMemberCountRow::getRoleId).toList();
        Map<String, Role> roles = roleIds.isEmpty() ? Map.of()
                : roleMapper.selectBatchIds(roleIds).stream()
                        .collect(Collectors.toMap(Role::getId, Function.identity()));
        List<RoleDistributionResponse> roleDistribution = rows.stream()
                .map(row -> new RoleDistributionResponse(row.getRoleId(),
                        roles.get(row.getRoleId()) == null ? null : roles.get(row.getRoleId()).getName(),
                        row.getMemberCount() == null ? 0 : row.getMemberCount()))
                .sorted(Comparator.comparingLong(RoleDistributionResponse::memberCount).reversed())
                .toList();

        LocalDate end = LocalDate.now(STAT_ZONE).minusDays(1);
        LocalDate start = end.minusDays(window - 1L);
        LocalDateTime from = start.atStartOfDay(STAT_ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime to = end.plusDays(1).atStartOfDay(STAT_ZONE).withZoneSameInstant(ZoneOffset.UTC)
                .toLocalDateTime();
        List<TrendPointResponse> newMemberTrend = channelStatsMapper.selectNewMemberTrend(channelId, from, to)
                .stream()
                .map(row -> new TrendPointResponse(row.getStatDate(),
                        row.getItemCount() == null ? 0 : row.getItemCount()))
                .toList();
        return new MemberStatsResponse(memberCount, roleDistribution, newMemberTrend);
    }

    /**
     * 手动补跑：默认昨日。
     */
    @Transactional
    public DailyStatsResponse aggregateManual(String channelId, LocalDate date, String actorId) {
        accessService.requirePermission(channelId, actorId, ChannelPermissions.DATA_VIEW);
        return aggregate(channelId, date == null ? LocalDate.now(STAT_ZONE).minusDays(1) : date);
    }

    private DailyStatsResponse ensureAggregated(String channelId, LocalDate date) {
        DailyChannelStats existing = dailyChannelStatsMapper.selectByChannelAndDate(channelId, date);
        if (existing != null) {
            return toResponse(existing);
        }
        return aggregate(channelId, date);
    }

    private int normalizeDays(Integer days) {
        if (days == null || days <= 0) {
            return DEFAULT_TREND_DAYS;
        }
        return Math.min(days, MAX_TREND_DAYS);
    }

    private long value(Long value) {
        return value == null ? 0 : value;
    }

    private BigDecimal rate(long numerator, long denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    private DailyStatsResponse empty(LocalDate date) {
        return new DailyStatsResponse(date, 0, 0, 0, 0, 0, 0, BigDecimal.ZERO,
                0, 0, 0, 0, 0, BigDecimal.ZERO);
    }

    private DailyStatsResponse toResponse(DailyChannelStats stats) {
        return new DailyStatsResponse(
                stats.getStatDate(),
                stats.getMemberCount() == null ? 0 : stats.getMemberCount(),
                stats.getNewMembers() == null ? 0 : stats.getNewMembers(),
                stats.getLostMembers() == null ? 0 : stats.getLostMembers(),
                stats.getVisitUv() == null ? 0 : stats.getVisitUv(),
                stats.getPostViews() == null ? 0 : stats.getPostViews(),
                stats.getActiveUsers() == null ? 0 : stats.getActiveUsers(),
                stats.getActiveRate() == null ? BigDecimal.ZERO : stats.getActiveRate(),
                stats.getPosts() == null ? 0 : stats.getPosts(),
                stats.getComments() == null ? 0 : stats.getComments(),
                stats.getLikes() == null ? 0 : stats.getLikes(),
                stats.getShares() == null ? 0 : stats.getShares(),
                stats.getInteractions() == null ? 0 : stats.getInteractions(),
                stats.getInteractionRate() == null ? BigDecimal.ZERO : stats.getInteractionRate());
    }

    private BoardStatResponse toBoardStat(BoardPostCountRow row) {
        return new BoardStatResponse(row.getBoardId(), row.getBoardName(),
                row.getPostCount() == null ? 0 : row.getPostCount());
    }

    private TopPostStatResponse toTopPost(Post post) {
        return new TopPostStatResponse(
                post.getId(),
                post.getTitle(),
                HtmlSanitizer.toPlainText(post.getContent()),
                post.getViewCount() == null ? 0 : post.getViewCount(),
                post.getLikeCount() == null ? 0 : post.getLikeCount(),
                post.getCommentCount() == null ? 0 : post.getCommentCount(),
                post.getShareCount() == null ? 0 : post.getShareCount(),
                ClockTimes.toInstant(post.getCreatedAt()));
    }
}
