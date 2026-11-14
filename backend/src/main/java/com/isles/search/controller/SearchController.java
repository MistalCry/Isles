package com.isles.search.controller;

import com.isles.common.pagination.PageResult;
import com.isles.common.security.CurrentUserProvider;
import com.isles.search.dto.SearchHistoryResponse;
import com.isles.search.dto.SearchItemResponse;
import com.isles.search.dto.SearchSuggestResponse;
import com.isles.search.service.SearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 搜索接口（docs/05 §2.5）：搜索本身公开只读；联想与历史需登录。
 */
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping
    public PageResult<SearchItemResponse> search(@RequestParam(required = false) String q,
                                                 @RequestParam(required = false) String type,
                                                 @RequestParam(required = false) String cursor,
                                                 @RequestParam(required = false) Integer limit) {
        return searchService.search(q, type, cursor, limit, currentUserProvider.currentUserIdOrNull());
    }

    @GetMapping("/suggest")
    public SearchSuggestResponse suggest(@RequestParam(required = false) String q) {
        return searchService.suggest(q, currentUserProvider.requireUserId());
    }

    @GetMapping("/history")
    public PageResult<SearchHistoryResponse> history() {
        return new PageResult<>(searchService.history(currentUserProvider.requireUserId()), null);
    }

    @DeleteMapping("/history")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearHistory() {
        searchService.clearHistory(currentUserProvider.requireUserId());
    }

    @DeleteMapping("/history/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteHistory(@PathVariable String id) {
        searchService.deleteHistory(id, currentUserProvider.requireUserId());
    }
}
