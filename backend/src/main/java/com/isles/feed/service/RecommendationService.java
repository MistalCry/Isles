package com.isles.feed.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.isles.channel.entity.Channel;
import com.isles.channel.mapper.ChannelMapper;
import com.isles.channel.mapper.ChannelMemberMapper;
import com.isles.channel.mapper.ChannelRecommendationMapper;
import com.isles.channel.mapper.row.CommonMemberRow;
import com.isles.common.time.ClockTimes;
import com.isles.feed.dto.RecommendationResponse;
import com.isles.feed.entity.ChannelRecommendation;
import com.isles.post.mapper.PostMapper;
import com.isles.post.mapper.row.ChannelPostCountRow;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 频道推荐（FR-HOT-03/04）：规则打分 = 共同成员×3 + 近 7 天新帖×2 + 成员/帖子量微调。
 *
 * <p>推荐理由按主导因素生成；结果按用户 upsert 到 channel_recommendations
 * （频道页的相似推荐不落库，避免污染全站推荐）。
 */
@Service
@RequiredArgsConstructor
public class RecommendationService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 20;
    private static final int CANDIDATE_MULTIPLIER = 3;
    private static final int MAX_CANDIDATES = 60;
    private static final int RECENT_DAYS = 7;
    private static final double COMMON_MEMBER_WEIGHT = 3.0;
    private static final double RECENT_POST_WEIGHT = 2.0;
    private static final double MEMBER_COUNT_WEIGHT = 0.01;
    private static final double POST_COUNT_WEIGHT = 0.005;

    private final ChannelMapper channelMapper;
    private final ChannelMemberMapper channelMemberMapper;
    private final PostMapper postMapper;
    private final ChannelRecommendationMapper channelRecommendationMapper;

    private record Scored(Channel channel, double score, String reason) {
    }

    /**
     * 生成推荐列表；channelId 非空时以该频道为相似基准（频道页推荐）。
     */
    @Transactional
    public List<RecommendationResponse> recommendations(String userId, String channelId, Integer limit) {
        return recommendations(userId, channelId, limit, null);
    }

    /**
     * 生成推荐列表；seed 非空时在「高质量候选窗口」内轮换，用于前端「刷新」按钮换一批。
     */
    @Transactional
    public List<RecommendationResponse> recommendations(String userId, String channelId, Integer limit, Long seed) {
        int size = normalizeLimit(limit);
        List<String> baseChannelIds = channelId == null || channelId.isBlank()
                ? channelMapper.selectJoinedChannels(userId).stream().map(Channel::getId).toList()
                : List.of(channelId);
        List<Channel> candidates = channelMapper.selectRecommendationCandidates(userId,
                Math.min(MAX_CANDIDATES, size * CANDIDATE_MULTIPLIER));
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<String> candidateIds = candidates.stream().map(Channel::getId).toList();
        Map<String, Long> commonMembers = loadCommonMembers(candidateIds, baseChannelIds, userId);
        Map<String, Long> recentPosts = loadRecentPosts(candidateIds);
        LocalDateTime generatedAt = ClockTimes.nowUtc();

        List<Scored> scored = new ArrayList<>(candidates.size());
        for (Channel channel : candidates) {
            long common = commonMembers.getOrDefault(channel.getId(), 0L);
            long recent = recentPosts.getOrDefault(channel.getId(), 0L);
            long memberCount = channel.getMemberCount() == null ? 0 : channel.getMemberCount();
            long postCount = channel.getPostCount() == null ? 0 : channel.getPostCount();
            double score = common * COMMON_MEMBER_WEIGHT + recent * RECENT_POST_WEIGHT
                    + memberCount * MEMBER_COUNT_WEIGHT + postCount * POST_COUNT_WEIGHT;
            scored.add(new Scored(channel, score, buildReason(common, recent, memberCount, postCount)));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<Scored> top = rotate(scored, size, seed);

        if (channelId == null || channelId.isBlank()) {
            persist(userId, top, generatedAt);
        }
        return top.stream().map(this::toResponse).toList();
    }

    /**
     * 取前 size 条；带 seed 时在 size*2 的候选窗口内循环移位，保证「刷新」能换一批但仍保持质量。
     */
    private List<Scored> rotate(List<Scored> scored, int size, Long seed) {
        if (seed == null || scored.size() <= size) {
            return scored.subList(0, Math.min(size, scored.size()));
        }
        int window = Math.min(scored.size(), size * 2);
        int offset = (int) Math.floorMod(seed, window);
        List<Scored> windowed = new ArrayList<>(window);
        for (int i = 0; i < window; i++) {
            windowed.add(scored.get((i + offset) % window));
        }
        return windowed.subList(0, Math.min(size, windowed.size()));
    }

    private Map<String, Long> loadCommonMembers(List<String> candidateIds, List<String> baseChannelIds,
                                                String userId) {
        if (baseChannelIds.isEmpty()) {
            return Map.of();
        }
        List<CommonMemberRow> rows = channelMemberMapper.countCommonMembers(candidateIds, baseChannelIds, userId);
        return rows.stream().collect(Collectors.toMap(CommonMemberRow::getChannelId,
                row -> row.getCommonCount() == null ? 0L : row.getCommonCount()));
    }

    private Map<String, Long> loadRecentPosts(List<String> candidateIds) {
        List<ChannelPostCountRow> rows = postMapper.countPostsByChannelIdsSince(candidateIds,
                ClockTimes.nowUtc().minusDays(RECENT_DAYS));
        return rows.stream().collect(Collectors.toMap(ChannelPostCountRow::getChannelId,
                row -> row.getPostCount() == null ? 0L : row.getPostCount()));
    }

    private void persist(String userId, List<Scored> top, LocalDateTime generatedAt) {
        channelRecommendationMapper.delete(Wrappers.<ChannelRecommendation>lambdaQuery()
                .eq(ChannelRecommendation::getUserId, userId));
        for (Scored scored : top) {
            ChannelRecommendation recommendation = new ChannelRecommendation();
            recommendation.setId(com.isles.common.id.UlidGenerator.generate());
            recommendation.setUserId(userId);
            recommendation.setChannelId(scored.channel().getId());
            recommendation.setScore(BigDecimal.valueOf(scored.score()).setScale(4, RoundingMode.HALF_UP));
            recommendation.setReason(scored.reason());
            recommendation.setGeneratedAt(generatedAt);
            channelRecommendationMapper.upsert(recommendation);
        }
        log.info("生成频道推荐 userId={} count={}", userId, top.size());
    }

    private String buildReason(long common, long recent, long memberCount, long postCount) {
        if (common > 0) {
            return "有 " + common + " 位你频道里的成员也在这里";
        }
        if (recent >= 3) {
            return "近 7 天新增 " + recent + " 篇帖子";
        }
        if (memberCount >= 100) {
            return "热门频道，已有 " + memberCount + " 人加入";
        }
        if (postCount > 0) {
            return "与你兴趣相似的频道";
        }
        return "新频道，抢先加入";
    }

    private RecommendationResponse toResponse(Scored scored) {
        Channel channel = scored.channel();
        return new RecommendationResponse(
                channel.getId(),
                channel.getChannelNo(),
                channel.getName(),
                channel.getAvatarUrl(),
                channel.getCoverUrl(),
                channel.getDescription(),
                channel.getMemberCount(),
                channel.getPostCount(),
                scored.score(),
                scored.reason());
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
