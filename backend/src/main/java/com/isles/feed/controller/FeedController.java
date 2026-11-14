package com.isles.feed.controller;

import com.isles.common.pagination.PageResult;
import com.isles.common.security.CurrentUserProvider;
import com.isles.feed.service.FeedService;
import com.isles.post.dto.PostResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 频道动态（docs/05 §2.4）：GET /feed?sort=latest|hot&cursor=&limit=，需登录。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class FeedController {

    private final FeedService feedService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping("/feed")
    public PageResult<PostResponse> feed(@RequestParam(required = false, defaultValue = "latest") String sort,
                                         @RequestParam(required = false) String cursor,
                                         @RequestParam(required = false) Integer limit) {
        return feedService.feed(sort, cursor, limit, currentUserProvider.requireUserId());
    }
}
