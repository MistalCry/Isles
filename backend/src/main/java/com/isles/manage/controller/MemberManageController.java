package com.isles.manage.controller;

import com.isles.common.pagination.OffsetPage;
import com.isles.common.security.CurrentUserProvider;
import com.isles.manage.dto.AssignRoleRequest;
import com.isles.manage.dto.MemberResponse;
import com.isles.manage.dto.MuteRequest;
import com.isles.manage.service.MemberManageService;
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
 * 成员管理（FR-MGR-09）：列表/搜索、禁言/解除、移除、分配身份组。
 */
@RestController
@RequestMapping("/api/v1/channels")
@RequiredArgsConstructor
public class MemberManageController {

    private final MemberManageService memberManageService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping("/{id}/members")
    public OffsetPage<MemberResponse> listMembers(@PathVariable String id,
                                                  @RequestParam(required = false) String keyword,
                                                  @RequestParam(required = false) String roleId,
                                                  @RequestParam(required = false, defaultValue = "1") Integer page,
                                                  @RequestParam(required = false, defaultValue = "20") Integer size) {
        return memberManageService.listOffset(id, keyword, roleId, page, size,
                currentUserProvider.requireUserId());
    }

    @PostMapping("/{id}/members/{userId}/mute")
    public MemberResponse mute(@PathVariable String id, @PathVariable String userId,
                               @Valid @RequestBody MuteRequest request) {
        return memberManageService.mute(id, userId, request, currentUserProvider.requireUserId());
    }

    @DeleteMapping("/{id}/members/{userId}/mute")
    public MemberResponse unmute(@PathVariable String id, @PathVariable String userId) {
        return memberManageService.unmute(id, userId, currentUserProvider.requireUserId());
    }

    @DeleteMapping("/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable String id, @PathVariable String userId) {
        memberManageService.remove(id, userId, currentUserProvider.requireUserId());
    }

    @PostMapping("/{id}/members/{userId}/role")
    public MemberResponse assignRole(@PathVariable String id, @PathVariable String userId,
                                     @Valid @RequestBody AssignRoleRequest request) {
        return memberManageService.assignRole(id, userId, request.roleId(), currentUserProvider.requireUserId());
    }
}
