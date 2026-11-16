package com.isles.apps.controller;

import com.isles.apps.dto.ActivityDtos;
import com.isles.apps.service.ActivityService;
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

/**
 * 频道活动接口（V10 / docs/05 §2.14）。
 *
 * <p>浏览：频道可见；管理（创建/编辑/发布/取消/名单）：{@code channel.settings}；报名：频道成员。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ActivityController {

    private final ActivityService activityService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping("/channels/{channelId}/activities")
    public ActivityDtos.ActivityPage list(@PathVariable String channelId,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size) {
        return activityService.list(channelId, currentUserProvider.currentUserIdOrNull(), status, page, size);
    }

    @PostMapping("/channels/{channelId}/activities")
    @ResponseStatus(HttpStatus.CREATED)
    public ActivityDtos.ActivityView create(@PathVariable String channelId,
                                            @Valid @RequestBody ActivityDtos.SaveActivityRequest request) {
        return activityService.create(channelId, request, currentUserProvider.requireUserId());
    }

    @GetMapping("/activities/{activityId}")
    public ActivityDtos.ActivityView detail(@PathVariable String activityId) {
        return activityService.detail(activityId, currentUserProvider.currentUserIdOrNull());
    }

    @PutMapping("/activities/{activityId}")
    public ActivityDtos.ActivityView update(@PathVariable String activityId,
                                            @Valid @RequestBody ActivityDtos.SaveActivityRequest request) {
        return activityService.update(activityId, request, currentUserProvider.requireUserId());
    }

    @DeleteMapping("/activities/{activityId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String activityId) {
        activityService.delete(activityId, currentUserProvider.requireUserId());
    }

    @PostMapping("/activities/{activityId}/publish")
    public ActivityDtos.ActivityView publish(@PathVariable String activityId) {
        return activityService.publish(activityId, currentUserProvider.requireUserId());
    }

    @PostMapping("/activities/{activityId}/cancel")
    public ActivityDtos.ActivityView cancel(@PathVariable String activityId) {
        return activityService.cancel(activityId, currentUserProvider.requireUserId());
    }

    /** 报名（幂等：已报名再调用返回当前状态）。 */
    @PostMapping("/activities/{activityId}/signup")
    public ActivityDtos.ActivityView signup(@PathVariable String activityId) {
        return activityService.signup(activityId, currentUserProvider.requireUserId());
    }

    /** 取消报名。 */
    @DeleteMapping("/activities/{activityId}/signup")
    public ActivityDtos.ActivityView cancelSignup(@PathVariable String activityId) {
        return activityService.cancelSignup(activityId, currentUserProvider.requireUserId());
    }

    /** 报名名单（管理端）。 */
    @GetMapping("/activities/{activityId}/signups")
    public ActivityDtos.SignupList signups(@PathVariable String activityId) {
        return activityService.signups(activityId, currentUserProvider.requireUserId());
    }
}
