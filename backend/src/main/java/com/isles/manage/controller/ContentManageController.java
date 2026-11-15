package com.isles.manage.controller;

import com.isles.common.pagination.OffsetPage;
import com.isles.common.security.CurrentUserProvider;
import com.isles.manage.dto.BatchPostRequest;
import com.isles.manage.dto.BatchResultResponse;
import com.isles.manage.service.ContentManageService;
import com.isles.post.dto.PostResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 内容管理（FR-MGR-05）：管理列表筛选、置顶/精华/删除、批量操作、评论删除。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ContentManageController {

    private final ContentManageService contentManageService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping("/channels/{channelId}/posts/manage")
    public OffsetPage<PostResponse> manageList(
            @PathVariable String channelId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String authorId,
            @RequestParam(required = false) String boardId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "20") Integer size) {
        return contentManageService.manageListOffset(channelId, keyword, authorId, boardId, status, from, to,
                page, size, currentUserProvider.requireUserId());
    }

    @PostMapping("/channels/{channelId}/posts/{postId}/pin")
    public PostResponse pinPost(@PathVariable String channelId, @PathVariable String postId) {
        return contentManageService.pin(channelId, postId, true, currentUserProvider.requireUserId());
    }

    @DeleteMapping("/channels/{channelId}/posts/{postId}/pin")
    public PostResponse unpinPost(@PathVariable String channelId, @PathVariable String postId) {
        return contentManageService.pin(channelId, postId, false, currentUserProvider.requireUserId());
    }

    @PostMapping("/channels/{channelId}/posts/{postId}/essence")
    public PostResponse essencePost(@PathVariable String channelId, @PathVariable String postId) {
        return contentManageService.essence(channelId, postId, true, currentUserProvider.requireUserId());
    }

    @DeleteMapping("/channels/{channelId}/posts/{postId}/essence")
    public PostResponse unessencePost(@PathVariable String channelId, @PathVariable String postId) {
        return contentManageService.essence(channelId, postId, false, currentUserProvider.requireUserId());
    }

    @DeleteMapping("/channels/{channelId}/posts/{postId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePost(@PathVariable String channelId, @PathVariable String postId) {
        contentManageService.deletePost(channelId, postId, currentUserProvider.requireUserId());
    }

    @PostMapping("/channels/{channelId}/posts/batch")
    public BatchResultResponse batch(@PathVariable String channelId,
                                     @Valid @RequestBody BatchPostRequest request) {
        return new BatchResultResponse(contentManageService.batch(channelId, request,
                currentUserProvider.requireUserId()));
    }

    /**
     * 作者或管理员删除帖子（帖子详情页入口）。
     */
    @DeleteMapping("/posts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePostByViewer(@PathVariable String id) {
        contentManageService.deletePost(null, id, currentUserProvider.requireUserId());
    }

    /**
     * 作者或管理员删除评论。
     */
    @DeleteMapping("/comments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteComment(@PathVariable String id) {
        contentManageService.deleteComment(id, currentUserProvider.requireUserId());
    }
}
