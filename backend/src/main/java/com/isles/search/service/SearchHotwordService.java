package com.isles.search.service;

import com.isles.common.time.ClockTimes;
import com.isles.search.dto.HotKeywordResponse;
import com.isles.search.entity.HotKeyword;
import com.isles.search.mapper.HotKeywordMapper;
import com.isles.search.mapper.SearchLogMapper;
import com.isles.search.mapper.row.KeywordCountRow;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 搜索热词聚合（FR-SRCH-03）：search_logs 按窗口聚合 → hot_keywords 绝对值覆盖写入（可重复执行）。
 *
 * <p>每日 00:20 聚合近 7 天搜索日志；联想与热词展示优先读 hot_keywords，为空时回退实时聚合。
 */
@Service
@RequiredArgsConstructor
public class SearchHotwordService {

    private static final Logger log = LoggerFactory.getLogger(SearchHotwordService.class);
    private static final int WINDOW_DAYS = 7;
    private static final int MAX_KEYWORDS = 200;

    private final SearchLogMapper searchLogMapper;
    private final HotKeywordMapper hotKeywordMapper;

    @Scheduled(cron = "${app.search.hotword-cron:0 20 0 * * ?}")
    public void scheduledAggregate() {
        aggregate();
    }

    /**
     * 聚合近 7 天搜索日志，返回写入的热词数量。
     */
    @Transactional
    public int aggregate() {
        LocalDateTime since = ClockTimes.nowUtc().minusDays(WINDOW_DAYS);
        List<KeywordCountRow> rows = searchLogMapper.selectKeywordCounts(since, MAX_KEYWORDS);
        LocalDateTime now = ClockTimes.nowUtc();
        for (KeywordCountRow row : rows) {
            HotKeyword keyword = new HotKeyword();
            keyword.setId(com.isles.common.id.UlidGenerator.generate());
            keyword.setKeyword(row.getKeyword());
            keyword.setSearchCount(row.getSearchCount() == null ? 0 : row.getSearchCount());
            keyword.setLastSearchedAt(row.getLastSearchedAt());
            keyword.setCreatedAt(now);
            keyword.setUpdatedAt(now);
            hotKeywordMapper.upsert(keyword);
        }
        log.info("搜索热词聚合完成 count={}", rows.size());
        return rows.size();
    }

    /**
     * 热词榜（为空时回退实时聚合，保证新库可用）。
     */
    public List<HotKeywordResponse> top(int limit) {
        int size = Math.max(1, Math.min(limit, 50));
        List<HotKeyword> keywords = hotKeywordMapper.selectTop(size);
        if (!keywords.isEmpty()) {
            return keywords.stream().map(this::toResponse).toList();
        }
        return searchLogMapper.selectKeywordCounts(ClockTimes.nowUtc().minusDays(WINDOW_DAYS), size).stream()
                .map(row -> new HotKeywordResponse(row.getKeyword(),
                        row.getSearchCount() == null ? 0 : row.getSearchCount(),
                        ClockTimes.toInstant(row.getLastSearchedAt())))
                .toList();
    }

    /**
     * 联想用前缀热词；为空时回退实时聚合的前缀匹配。
     */
    public List<String> keywordsByPrefix(String pattern, int limit) {
        List<HotKeyword> keywords = hotKeywordMapper.selectByPrefix(pattern, limit);
        if (!keywords.isEmpty()) {
            return keywords.stream().map(HotKeyword::getKeyword).toList();
        }
        return searchLogMapper.selectPopularKeywords(pattern, limit);
    }

    private HotKeywordResponse toResponse(HotKeyword keyword) {
        return new HotKeywordResponse(keyword.getKeyword(),
                keyword.getSearchCount() == null ? 0 : keyword.getSearchCount(),
                ClockTimes.toInstant(keyword.getLastSearchedAt()));
    }
}
