package com.isles.channel.controller;

import com.isles.channel.dto.InviteAcceptResponse;
import com.isles.channel.service.ChannelService;
import com.isles.common.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 邀请链接使用（docs/05 §2.2）：立即加入或提交加入申请。
 */
@RestController
@RequestMapping("/api/v1/invites")
@RequiredArgsConstructor
public class InviteController {

    private final ChannelService channelService;
    private final CurrentUserProvider currentUserProvider;

    @PostMapping("/{token}/accept")
    public InviteAcceptResponse acceptInvite(@PathVariable String token) {
        return channelService.acceptInvite(token, currentUserProvider.requireUserId());
    }
}
