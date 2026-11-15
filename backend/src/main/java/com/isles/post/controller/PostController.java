package com.isles.post.controller;

import com.isles.common.ratelimit.RateLimit;
import com.isles.common.ratelimit.RateLimitPolicy;
import com.isles.common.pagination.PageResult;
import com.isles.common.security.CurrentUserProvider;
import com.isles.interaction.dto.LikeStateResponse;
import com.isles.interaction.service.InteractionService;
import com.isles.post.dto.CreatePostRequest;
import com.isles.post.dto.UpdatePostRequest;
import com.isles.post.dto.PostResponse;
import com.isles.post.dto.ShareResponse;
import com.isles.post.service.PostService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 帖子接口（docs/05 §2.3）：频道帖子流/详情/点赞/分享。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;
    private final InteractionService interactionService;
    private final CurrentUserProvider currentUserProvider;

    @RateLimit(RateLimitPolicy.POST_CREATE)
    @PostMapping("/channels/{channelId}/posts")
    @ResponseStatus(HttpStatus.CREATED)
    public PostResponse createPost(@PathVariable String channelId, @Valid @RequestBody CreatePostRequest request) {
        return postService.createPost(channelId, request, currentUserProvider.requireUserId());
    }

    @GetMapping("/channels/{channelId}/posts")
    public PageResult<PostResponse> listChannelPosts(@PathVariable String channelId,
                                                     @RequestParam(required = false, defaultValue = "latest") String tab,
                                                     @RequestParam(required = false) String boardId,
                                                     @RequestParam(required = false) String cursor,
                                                     @RequestParam(required = false) Integer limit) {
        return postService.listChannelPosts(channelId, tab, boardId, cursor, limit,
                currentUserProvider.currentUserIdOrNull());
    }

    @GetMapping("/posts/{id}")
    public PostResponse getPost(@PathVariable String id) {
        return postService.getPostDetail(id, currentUserProvider.currentUserIdOrNull());
    }

    /**
     * 编辑帖子（FR-POST-07）：仅作者本人可调用。
     */
    @PatchMapping("/posts/{id}")
    public PostResponse updatePost(@PathVariable String id, @Valid @RequestBody UpdatePostRequest request) {
        return postService.updatePost(id, request, currentUserProvider.requireUserId());
    }

    @PostMapping("/posts/{id}/like")
    public LikeStateResponse likePost(@PathVariable String id) {
        return interactionService.likePost(id, currentUserProvider.requireUserId());
    }

    @DeleteMapping("/posts/{id}/like")
    public LikeStateResponse unlikePost(@PathVariable String id) {
        return interactionService.unlikePost(id, currentUserProvider.requireUserId());
    }

    @PostMapping("/posts/{id}/share")
    public ShareResponse sharePost(@PathVariable String id) {
        return new ShareResponse(interactionService.sharePost(id, currentUserProvider.requireUserId()));
    }
}
