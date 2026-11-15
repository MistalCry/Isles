package com.isles.search.controller;

import com.isles.common.pagination.PageResult;
import com.isles.common.security.CurrentUserProvider;
import com.isles.post.dto.PostResponse;
import com.isles.search.service.SearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 频道内搜索（FR-SRCH-08）：范围限定当前频道，公开可读（私密仅成员可见由可见性校验兜底）。
 */
@RestController
@RequestMapping("/api/v1/channels")
@RequiredArgsConstructor
public class ChannelSearchController {

    private final SearchService searchService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping("/{id}/search")
    public PageResult<PostResponse> channelSearch(@PathVariable String id,
                                                  @RequestParam(required = false) String q,
                                                  @RequestParam(required = false) String boardId,
                                                  @RequestParam(required = false) String sort,
                                                  @RequestParam(required = false) String cursor,
                                                  @RequestParam(required = false) Integer limit) {
        return searchService.channelSearch(id, q, boardId, sort, cursor, limit,
                currentUserProvider.currentUserIdOrNull());
    }
}
