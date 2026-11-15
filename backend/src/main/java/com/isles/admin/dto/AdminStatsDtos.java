package com.isles.admin.dto;

import java.time.Instant;
import java.util.List;

/**
 * 平台趋势与热词接口模型（docs/11 §5.5）。
 */
public final class AdminStatsDtos {

    private AdminStatsDtos() {
    }

    /**
     * @param date ISO 日期（UTC），缺失日期由服务层补零
     */
    public record TrendPoint(
            String date,
            long newUsers,
            long newPosts,
            long newComments,
            long activeUsers) {
    }

    public record TrendResponse(List<TrendPoint> points) {
    }

    public record HotKeywordView(
            String id,
            String keyword,
            long searchCount,
            Instant lastSearchedAt) {
    }

    public record HotKeywordListResponse(List<HotKeywordView> items) {
    }
}
