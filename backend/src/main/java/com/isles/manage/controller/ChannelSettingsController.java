package com.isles.manage.controller;

import com.isles.channel.dto.ChannelResponse;
import com.isles.common.security.CurrentUserProvider;
import com.isles.manage.dto.ChannelSettingsResponse;
import com.isles.manage.dto.MuteRequest;
import com.isles.manage.dto.UpdateChannelRequest;
import com.isles.manage.service.ChannelSettingsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 频道设置（FR-CH-10/11/12/14、FR-MGR-04）：资料修改、设置面板、全员禁言、退出、解散。
 */
@RestController
@RequestMapping("/api/v1/channels")
@RequiredArgsConstructor
public class ChannelSettingsController {

    private final ChannelSettingsService channelSettingsService;
    private final CurrentUserProvider currentUserProvider;

    @PatchMapping("/{id}")
    public ChannelResponse updateChannel(@PathVariable String id,
                                         @Valid @RequestBody UpdateChannelRequest request) {
        return channelSettingsService.updateChannel(id, request, currentUserProvider.requireUserId());
    }

    @GetMapping("/{id}/settings")
    public ChannelSettingsResponse getSettings(@PathVariable String id) {
        return channelSettingsService.getSettings(id, currentUserProvider.requireUserId());
    }

    @PostMapping("/{id}/mute-all")
    public ChannelSettingsResponse muteAll(@PathVariable String id, @Valid @RequestBody MuteRequest request) {
        return channelSettingsService.muteAll(id, request, currentUserProvider.requireUserId());
    }

    @DeleteMapping("/{id}/mute-all")
    public ChannelSettingsResponse cancelMuteAll(@PathVariable String id) {
        return channelSettingsService.cancelMuteAll(id, currentUserProvider.requireUserId());
    }

    @PostMapping("/{id}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@PathVariable String id) {
        channelSettingsService.leaveChannel(id, currentUserProvider.requireUserId());
    }

    @PostMapping("/{id}/dissolve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dissolve(@PathVariable String id) {
        channelSettingsService.dissolveChannel(id, currentUserProvider.requireUserId());
    }
}
