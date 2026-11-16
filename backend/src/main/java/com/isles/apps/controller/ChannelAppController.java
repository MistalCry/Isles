package com.isles.apps.controller;

import com.isles.apps.dto.AppDtos;
import com.isles.apps.service.ChannelAppService;
import com.isles.common.security.CurrentUserProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 频道应用接口（V10 / docs/05 §2.14）。
 *
 * <p>读：频道可见即可（默认只返回启用项，{@code includeDisabled=true} 需 {@code channel.settings}）；
 * 写：{@code channel.settings} 权限点。
 */
@RestController
@RequestMapping("/api/v1/channels/{channelId}/apps")
@RequiredArgsConstructor
public class ChannelAppController {

    private final ChannelAppService appService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping
    public List<AppDtos.AppView> list(@PathVariable String channelId,
                                      @RequestParam(required = false, defaultValue = "false") boolean includeDisabled) {
        return appService.list(channelId, currentUserProvider.currentUserIdOrNull(), includeDisabled);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AppDtos.AppView create(@PathVariable String channelId,
                                  @Valid @RequestBody AppDtos.SaveAppRequest request) {
        return appService.create(channelId, request, currentUserProvider.requireUserId());
    }

    @PutMapping("/{appId}")
    public AppDtos.AppView update(@PathVariable String channelId, @PathVariable String appId,
                                  @Valid @RequestBody AppDtos.SaveAppRequest request) {
        return appService.update(channelId, appId, request, currentUserProvider.requireUserId());
    }

    @DeleteMapping("/{appId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String channelId, @PathVariable String appId) {
        appService.delete(channelId, appId, currentUserProvider.requireUserId());
    }

    /**
     * 批量重排：按 appIds 顺序写入 sort_order（0 起）。
     */
    @PutMapping("/order")
    public List<AppDtos.AppView> reorder(@PathVariable String channelId,
                                         @Valid @RequestBody AppDtos.ReorderRequest request) {
        return appService.reorder(channelId, request, currentUserProvider.requireUserId());
    }
}
