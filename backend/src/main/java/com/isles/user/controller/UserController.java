package com.isles.user.controller;

import com.isles.common.security.CurrentUserProvider;
import com.isles.user.dto.UpdateProfileRequest;
import com.isles.user.dto.UserCardResponse;
import com.isles.user.dto.UserResponse;
import com.isles.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户资料接口（docs/05 §2.1）：公开资料/用户卡片可匿名访问，修改资料需登录。
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping("/{id}")
    public UserResponse getPublicUser(@PathVariable String id) {
        return userService.getPublicUser(id);
    }

    @GetMapping("/{id}/card")
    public UserCardResponse getUserCard(@PathVariable String id,
                                        @RequestParam(required = false) String channelId) {
        return userService.getUserCard(id, channelId, currentUserProvider.currentUserIdOrNull());
    }

    @PatchMapping("/me")
    public UserResponse updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        return userService.updateProfile(currentUserProvider.requireUserId(), request);
    }
}
