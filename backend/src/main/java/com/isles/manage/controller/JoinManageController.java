package com.isles.manage.controller;

import com.isles.common.pagination.PageResult;
import com.isles.common.security.CurrentUserProvider;
import com.isles.manage.dto.InviteResponse;
import com.isles.manage.dto.JoinRequestResponse;
import com.isles.manage.dto.RejectJoinRequest;
import com.isles.manage.service.JoinManageService;
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
 * 邀请链接管理 + 加入审批（FR-CH-15/16）。
 */
@RestController
@RequestMapping("/api/v1/channels")
@RequiredArgsConstructor
public class JoinManageController {

    private final JoinManageService joinManageService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping("/{id}/invites")
    public PageResult<InviteResponse> listInvites(@PathVariable String id,
                                                  @RequestParam(required = false) String cursor,
                                                  @RequestParam(required = false) Integer limit) {
        return joinManageService.listInvites(id, cursor, limit, currentUserProvider.requireUserId());
    }

    @DeleteMapping("/{id}/invites/{inviteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeInvite(@PathVariable String id, @PathVariable String inviteId) {
        joinManageService.revokeInvite(id, inviteId, currentUserProvider.requireUserId());
    }

    @GetMapping("/{id}/join-requests")
    public PageResult<JoinRequestResponse> listJoinRequests(@PathVariable String id,
                                                            @RequestParam(required = false) String status,
                                                            @RequestParam(required = false) String cursor,
                                                            @RequestParam(required = false) Integer limit) {
        return joinManageService.listJoinRequests(id, status, cursor, limit,
                currentUserProvider.requireUserId());
    }

    @PostMapping("/{id}/join-requests/{requestId}/approve")
    public JoinRequestResponse approve(@PathVariable String id, @PathVariable String requestId) {
        return joinManageService.approve(id, requestId, currentUserProvider.requireUserId());
    }

    @PostMapping("/{id}/join-requests/{requestId}/reject")
    public JoinRequestResponse reject(@PathVariable String id, @PathVariable String requestId,
                                      @Valid @RequestBody(required = false) RejectJoinRequest request) {
        String note = request == null ? null : request.note();
        return joinManageService.reject(id, requestId, note, currentUserProvider.requireUserId());
    }
}
