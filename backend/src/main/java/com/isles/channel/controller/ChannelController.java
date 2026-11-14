package com.isles.channel.controller;

import com.isles.channel.dto.BoardResponse;
import com.isles.channel.dto.ChannelResponse;
import com.isles.channel.dto.CreateChannelRequest;
import com.isles.channel.dto.InviteCreateRequest;
import com.isles.channel.dto.InviteCreateResponse;
import com.isles.channel.service.ChannelService;
import com.isles.common.pagination.PageResult;
import com.isles.common.security.CurrentUserProvider;
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
 * 频道接口（docs/05 §2.2）。详情/板块为公开只读，其余需登录。
 */
@RestController
@RequestMapping("/api/v1/channels")
@RequiredArgsConstructor
public class ChannelController {

    private final ChannelService channelService;
    private final CurrentUserProvider currentUserProvider;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ChannelResponse createChannel(@Valid @RequestBody CreateChannelRequest request) {
        return channelService.createChannel(request, currentUserProvider.requireUserId());
    }

    @GetMapping("/mine")
    public PageResult<ChannelResponse> myChannels() {
        return new PageResult<>(channelService.getMyChannels(currentUserProvider.requireUserId()), null);
    }

    @GetMapping("/{id}")
    public ChannelResponse getChannel(@PathVariable String id) {
        return channelService.getChannel(id, currentUserProvider.currentUserIdOrNull());
    }

    @PostMapping("/{id}/join")
    public ChannelResponse joinChannel(@PathVariable String id) {
        return channelService.joinChannel(id, currentUserProvider.requireUserId());
    }

    @PostMapping("/{id}/invites")
    @ResponseStatus(HttpStatus.CREATED)
    public InviteCreateResponse createInvite(@PathVariable String id,
                                             @Valid @RequestBody InviteCreateRequest request) {
        return channelService.createInvite(id, request, currentUserProvider.requireUserId());
    }

    @GetMapping("/{id}/boards")
    public PageResult<BoardResponse> listBoards(@PathVariable String id) {
        return new PageResult<>(channelService.listBoards(id, currentUserProvider.currentUserIdOrNull()), null);
    }
}
