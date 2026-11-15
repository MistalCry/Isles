package com.isles.comment.controller;

import com.isles.common.ratelimit.RateLimit;
import com.isles.common.ratelimit.RateLimitPolicy;
import com.isles.comment.dto.CommentResponse;
import com.isles.comment.dto.CreateCommentRequest;
import com.isles.comment.service.CommentService;
import com.isles.common.pagination.PageResult;
import com.isles.common.security.CurrentUserProvider;
import com.isles.interaction.dto.LikeStateResponse;
import com.isles.interaction.service.InteractionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

/**
 * 评论接口（docs/05 §2.3）：列表/发表/点赞。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;
    private final InteractionService interactionService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping("/posts/{postId}/comments")
    public PageResult<CommentResponse> listComments(@PathVariable String postId,
                                                    @RequestParam(required = false, defaultValue = "likes") String sort,
                                                    @RequestParam(required = false) String cursor,
                                                    @RequestParam(required = false) Integer limit) {
        return commentService.listComments(postId, sort, cursor, limit, currentUserProvider.currentUserIdOrNull());
    }

    @RateLimit(RateLimitPolicy.COMMENT_CREATE)
    @PostMapping("/posts/{postId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentResponse createComment(@PathVariable String postId,
                                         @Valid @RequestBody CreateCommentRequest request) {
        return commentService.createComment(postId, request, currentUserProvider.requireUserId());
    }

    @PostMapping("/comments/{id}/like")
    public LikeStateResponse likeComment(@PathVariable String id) {
        return interactionService.likeComment(id, currentUserProvider.requireUserId());
    }

    @DeleteMapping("/comments/{id}/like")
    public LikeStateResponse unlikeComment(@PathVariable String id) {
        return interactionService.unlikeComment(id, currentUserProvider.requireUserId());
    }
}
