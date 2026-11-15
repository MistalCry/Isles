package com.isles.analytics.controller;

import com.isles.analytics.dto.ContentStatsResponse;
import com.isles.analytics.dto.DailyStatsResponse;
import com.isles.analytics.dto.MemberStatsResponse;
import com.isles.analytics.dto.StatsOverviewResponse;
import com.isles.analytics.service.StatsService;
import com.isles.common.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 数据中心接口（FR-MGR-06，docs/03 §8）：概览/趋势/内容分析/成员分析 + 手动补跑。
 */
@RestController
@RequestMapping("/api/v1/channels/{channelId}/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping("/overview")
    public StatsOverviewResponse overview(@PathVariable String channelId) {
        return statsService.overview(channelId, currentUserProvider.requireUserId());
    }

    @GetMapping("/trends")
    public List<DailyStatsResponse> trends(@PathVariable String channelId,
                                           @RequestParam(required = false) Integer days) {
        return statsService.trends(channelId, days, currentUserProvider.requireUserId());
    }

    @GetMapping("/content")
    public ContentStatsResponse content(@PathVariable String channelId) {
        return statsService.contentStats(channelId, currentUserProvider.requireUserId());
    }

    @GetMapping("/members")
    public MemberStatsResponse members(@PathVariable String channelId,
                                       @RequestParam(required = false) Integer days) {
        return statsService.memberStats(channelId, days, currentUserProvider.requireUserId());
    }

    /**
     * 手动触发聚合补跑（默认昨日）。
     */
    @PostMapping("/aggregate")
    public DailyStatsResponse aggregate(@PathVariable String channelId,
                                        @RequestParam(required = false)
                                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return statsService.aggregateManual(channelId, date, currentUserProvider.requireUserId());
    }
}
