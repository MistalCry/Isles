package com.isles.admin.service;

import com.isles.admin.dto.AdminStatsDtos;
import com.isles.admin.dto.AdminTrendRow;
import com.isles.admin.mapper.AdminTrendMapper;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.time.ClockTimes;
import com.isles.platform.audit.PlatformAuditActions;
import com.isles.platform.audit.PlatformAuditService;
import com.isles.search.entity.HotKeyword;
import com.isles.search.mapper.HotKeywordMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台趋势与热词维护（docs/11 §5.5）。
 *
 * <p>趋势按 UTC 自然日聚合，缺失日期补零，保证前端折线图连续；热词来自搜索聚合表，
 * 后台只做查看与下线（下线用于清理刷量词）。
 */
@Service
@RequiredArgsConstructor
public class AdminStatsService {

    private static final Logger log = LoggerFactory.getLogger(AdminStatsService.class);
    private static final int DEFAULT_DAYS = 30;
    private static final int MAX_DAYS = 90;
    private static final int DEFAULT_HOT_LIMIT = 50;
    private static final int MAX_HOT_LIMIT = 200;

    private final AdminTrendMapper trendMapper;
    private final HotKeywordMapper hotKeywordMapper;
    private final PlatformAuditService auditService;

    public AdminStatsDtos.TrendResponse trend(Integer days) {
        int window = days == null || days <= 0 ? DEFAULT_DAYS : Math.min(days, MAX_DAYS);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate start = today.minusDays(window - 1L);
        LocalDateTime from = start.atStartOfDay();

        Map<LocalDate, long[]> byDay = new LinkedHashMap<>();
        for (int i = 0; i < window; i++) {
            byDay.put(start.plusDays(i), new long[4]);
        }
        fill(byDay, 0, trendMapper.countUsersByDay(from));
        fill(byDay, 1, trendMapper.countPostsByDay(from));
        fill(byDay, 2, trendMapper.countCommentsByDay(from));
        fill(byDay, 3, trendMapper.countActiveUsersByDay(from));

        List<AdminStatsDtos.TrendPoint> points = new ArrayList<>(byDay.size());
        byDay.forEach((day, values) -> points.add(new AdminStatsDtos.TrendPoint(
                day.toString(), values[0], values[1], values[2], values[3])));
        return new AdminStatsDtos.TrendResponse(List.copyOf(points));
    }

    public List<AdminStatsDtos.HotKeywordView> hotKeywords(Integer limit) {
        int size = limit == null || limit <= 0 ? DEFAULT_HOT_LIMIT : Math.min(limit, MAX_HOT_LIMIT);
        return hotKeywordMapper.selectTop(size).stream()
                .map(keyword -> new AdminStatsDtos.HotKeywordView(
                        keyword.getId(),
                        keyword.getKeyword(),
                        keyword.getSearchCount() == null ? 0L : keyword.getSearchCount(),
                        ClockTimes.toInstant(keyword.getLastSearchedAt())))
                .toList();
    }

    @Transactional
    public void deleteHotKeyword(String id, String operatorId) {
        HotKeyword keyword = hotKeywordMapper.selectById(id);
        if (keyword == null) {
            throw new AppException(ErrorCode.RESOURCE_NOT_FOUND, "热词不存在");
        }
        hotKeywordMapper.deleteById(id);
        auditService.record(operatorId, null, PlatformAuditActions.HOT_KEYWORD_DELETE, "HOT_KEYWORD", id,
                Map.of("keyword", keyword.getKeyword()));
        log.info("平台热词：下线 id={} keyword={}", id, keyword.getKeyword());
    }

    private void fill(Map<LocalDate, long[]> byDay, int index, List<AdminTrendRow> rows) {
        Map<LocalDate, Long> values = rows.stream()
                .filter(row -> row.getDay() != null)
                .collect(java.util.stream.Collectors.toMap(AdminTrendRow::getDay,
                        row -> row.getTotal() == null ? 0L : row.getTotal(), (left, right) -> left));
        byDay.forEach((day, bucket) -> {
            Long value = values.get(day);
            bucket[index] = value == null ? 0L : value;
        });
    }
}
