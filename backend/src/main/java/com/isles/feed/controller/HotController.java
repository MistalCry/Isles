package com.isles.feed.controller;

import com.isles.common.pagination.PageResult;
import com.isles.common.security.CurrentUserProvider;
import com.isles.feed.dto.RecommendationResponse;
import com.isles.feed.service.HotService;
import com.isles.feed.service.RecommendationService;
import com.isles.post.dto.PostResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 发现与推荐（WP3，docs/05 §2.4）：
 * <ul>
 *   <li>GET /hot：全站热门讨论（公开只读）；</li>
 *   <li>GET /hot/recommendations：频道推荐（登录）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/hot")
@RequiredArgsConstructor
public class HotController {

    private final HotService hotService;
    private final RecommendationService recommendationService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping
    public PageResult<PostResponse> hot(@RequestParam(required = false) String range,
                                        @RequestParam(required = false) String cursor,
                                        @RequestParam(required = false) Integer limit) {
        return hotService.hot(range, cursor, limit, currentUserProvider.currentUserIdOrNull());
    }

    @GetMapping("/recommendations")
    public List<RecommendationResponse> recommendations(@RequestParam(required = false) String channelId,
                                                        @RequestParam(required = false) Integer limit,
                                                        @RequestParam(required = false) Long seed) {
        return recommendationService.recommendations(currentUserProvider.requireUserId(), channelId, limit, seed);
    }
}
