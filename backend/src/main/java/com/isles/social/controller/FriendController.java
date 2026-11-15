package com.isles.social.controller;

import com.isles.common.pagination.PageResult;
import com.isles.common.security.CurrentUserProvider;
import com.isles.social.dto.BlockedUserResponse;
import com.isles.social.dto.FriendRequestResponse;
import com.isles.social.dto.FriendResponse;
import com.isles.social.dto.FriendStatusResponse;
import com.isles.social.dto.FriendTargetRequest;
import com.isles.social.service.FriendService;
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
 * 好友接口（docs/03 FR-USER-05、docs/05 §1.1 统一分页/错误信封；WP2 新增，需补充 docs/05 契约）。
 *
 * <p>全部需要登录；错误码沿用 {@code common/error/ErrorCode}（新增 BLOCKED/FRIEND_REQUEST_* 等）。
 */
@RestController
@RequestMapping("/api/v1/friends")
@RequiredArgsConstructor
public class FriendController {

    private final FriendService friendService;
    private final CurrentUserProvider currentUserProvider;

    /**
     * 好友列表（按成为好友的时间倒序游标分页）。
     */
    @GetMapping
    public PageResult<FriendResponse> listFriends(@RequestParam(required = false) String cursor,
                                                  @RequestParam(required = false) Integer limit) {
        return friendService.listFriends(currentUserProvider.requireUserId(), cursor, limit);
    }

    /**
     * 好友申请列表：{@code direction=incoming}（默认，发给我的）/ {@code outgoing}（我发出的）。
     */
    @GetMapping("/requests")
    public PageResult<FriendRequestResponse> listRequests(
            @RequestParam(required = false, defaultValue = "incoming") String direction,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return friendService.listRequests(currentUserProvider.requireUserId(), direction, cursor, limit);
    }

    /**
     * 发起好友申请；对方此前已向我申请时返回 ACCEPTED（互相申请直接成为好友）。
     */
    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.CREATED)
    public FriendRequestResponse sendRequest(@Valid @RequestBody FriendTargetRequest request) {
        return friendService.sendRequest(currentUserProvider.requireUserId(), request.resolveTargetUserId());
    }

    /**
     * 同意好友申请（仅被申请方）。
     */
    @PostMapping("/requests/{id}/accept")
    public FriendRequestResponse acceptRequest(@PathVariable String id) {
        return friendService.acceptRequest(id, currentUserProvider.requireUserId());
    }

    /**
     * 拒绝好友申请（仅被申请方）。
     */
    @PostMapping("/requests/{id}/reject")
    public FriendRequestResponse rejectRequest(@PathVariable String id) {
        return friendService.rejectRequest(id, currentUserProvider.requireUserId());
    }

    /**
     * 黑名单列表（用于设置页取消拉黑）。
     */
    @GetMapping("/blocks")
    public PageResult<BlockedUserResponse> listBlocked(@RequestParam(required = false) String cursor,
                                                       @RequestParam(required = false) Integer limit) {
        return friendService.listBlocked(currentUserProvider.requireUserId(), cursor, limit);
    }

    /**
     * 拉黑用户（幂等；同时解除好友与待处理申请）。
     */
    @PostMapping("/blocks")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void block(@Valid @RequestBody FriendTargetRequest request) {
        friendService.block(currentUserProvider.requireUserId(), request.resolveTargetUserId());
    }

    /**
     * 取消拉黑（幂等）。
     */
    @DeleteMapping("/blocks/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unblock(@PathVariable String userId) {
        friendService.unblock(currentUserProvider.requireUserId(), userId);
    }

    /**
     * 删除好友（幂等）。
     */
    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeFriend(@PathVariable String userId) {
        friendService.removeFriend(currentUserProvider.requireUserId(), userId);
    }

    /**
     * 两个用户之间的关系状态（用户卡片/按钮展示）。
     */
    @GetMapping("/{userId}/status")
    public FriendStatusResponse getStatus(@PathVariable String userId) {
        return friendService.getStatus(currentUserProvider.requireUserId(), userId);
    }
}
