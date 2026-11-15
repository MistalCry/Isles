package com.isles.social.controller;

import com.isles.common.pagination.PageResult;
import com.isles.common.security.CurrentUserProvider;
import com.isles.social.dto.BlockedUserResponse;
import com.isles.social.service.FriendService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 拉黑接口的兼容路径（前端契约 {@code /users/{userId}/block}、{@code /users/blocked}）。
 *
 * <p>实现完全复用 {@link FriendService}，与 {@code /friends/blocks} 等价；放在 social 模块内，
 * 不修改 user 模块文件。注意：{@code GET /users/blocked} 在安全配置中命中"用户资料公开"规则，
 * 但控制器内部仍强制 {@link CurrentUserProvider#requireUserId()}，未登录返回 401，不会泄露黑名单。
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserBlockController {

    private final FriendService friendService;
    private final CurrentUserProvider currentUserProvider;

    /**
     * 拉黑用户（幂等；同时解除好友与待处理申请）。
     */
    @PostMapping("/{userId}/block")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void block(@PathVariable String userId) {
        friendService.block(currentUserProvider.requireUserId(), userId);
    }

    /**
     * 取消拉黑（幂等）。
     */
    @DeleteMapping("/{userId}/block")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unblock(@PathVariable String userId) {
        friendService.unblock(currentUserProvider.requireUserId(), userId);
    }

    /**
     * 黑名单列表（前端契约路径；与 {@code GET /friends/blocks} 等价）。
     */
    @GetMapping("/blocked")
    public PageResult<BlockedUserResponse> listBlocked(@RequestParam(required = false) String cursor,
                                                       @RequestParam(required = false) Integer limit) {
        return friendService.listBlocked(currentUserProvider.requireUserId(), cursor, limit);
    }
}
