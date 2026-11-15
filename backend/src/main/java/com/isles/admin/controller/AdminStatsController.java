package com.isles.admin.controller;

import com.isles.admin.dto.AdminStatsDtos;
import com.isles.admin.service.AdminStatsService;
import com.isles.common.security.CurrentUserProvider;
import com.isles.platform.permission.PlatformPermission;
import com.isles.platform.permission.RequirePlatformPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台数据与运营配置接口（docs/11 §5.5）：趋势曲线、热词维护。
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminStatsController {

    private final AdminStatsService statsService;
    private final CurrentUserProvider currentUserProvider;

    /**
     * 近 N 天趋势（默认 30，上限 90），缺失日期补零。
     */
    @GetMapping("/stats/trend")
    @RequirePlatformPermission(PlatformPermission.STATS_READ)
    public AdminStatsDtos.TrendResponse trend(@RequestParam(required = false) Integer days) {
        return statsService.trend(days);
    }

    @GetMapping("/hot-keywords")
    @RequirePlatformPermission(PlatformPermission.STATS_READ)
    public AdminStatsDtos.HotKeywordListResponse hotKeywords(@RequestParam(required = false) Integer limit) {
        return new AdminStatsDtos.HotKeywordListResponse(statsService.hotKeywords(limit));
    }

    /**
     * 下线热词（清理刷量词），审计留痕。
     */
    @DeleteMapping("/hot-keywords/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePlatformPermission(PlatformPermission.CONFIG_WRITE)
    public void deleteHotKeyword(@PathVariable String id) {
        statsService.deleteHotKeyword(id, currentUserProvider.requireUserId());
    }
}
