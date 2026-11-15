package com.isles.admin.controller;

import com.isles.common.security.CurrentUserProvider;
import com.isles.platform.penalty.PenaltyDtos;
import com.isles.platform.penalty.PenaltyService;
import com.isles.platform.permission.PlatformPermission;
import com.isles.platform.permission.RequirePlatformPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户处罚接口（docs/11 §5.4）：封禁 / 禁言 / 警告，支持撤销与历史查询。
 *
 * <p>与 {@code /users/{id}/ban} 的关系：后者是「封禁」的语义化语法糖，内部同样落到处罚记录。
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminPenaltyController {

    private final PenaltyService penaltyService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping("/users/{id}/penalties")
    @RequirePlatformPermission(PlatformPermission.USER_READ)
    public PenaltyDtos.PenaltyListResponse list(@PathVariable String id) {
        return new PenaltyDtos.PenaltyListResponse(penaltyService.listByUser(id));
    }

    @PostMapping("/users/{id}/penalties")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePlatformPermission(PlatformPermission.USER_PENALTY)
    public PenaltyDtos.PenaltyView create(@PathVariable String id,
                                          @Valid @RequestBody PenaltyDtos.CreatePenaltyRequest request) {
        return penaltyService.create(id, request, currentUserProvider.requireUserId());
    }

    @PostMapping("/penalties/{id}/revoke")
    @RequirePlatformPermission(PlatformPermission.USER_PENALTY)
    public PenaltyDtos.PenaltyView revoke(@PathVariable String id) {
        return penaltyService.revoke(id, currentUserProvider.requireUserId());
    }
}
