package com.isles.ai.controller;

import com.isles.ai.dto.AiRequests;
import com.isles.ai.dto.AiResponses;
import com.isles.ai.service.AiTaskService;
import com.isles.ai.service.ChannelAiSettingsService;
import com.isles.common.pagination.PageResult;
import com.isles.common.security.CurrentUserProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 频道助手接口（docs/03 FR-ASSIST-01/02/06、FR-MGR-08）：
 * 频道页面板信息、管理端设置、虚拟账号、任务日志与手动触发。
 *
 * <p>权限：面板信息要求频道可见；设置/日志/任务要求 {@code channel.ai.manage} 权限点（频道主/超级管理员）。
 */
@RestController
@RequestMapping("/api/v1/channels/{channelId}/ai")
@RequiredArgsConstructor
public class ChannelAssistantController {

    private final ChannelAiSettingsService settingsService;
    private final AiTaskService taskService;
    private final CurrentUserProvider currentUserProvider;

    /**
     * 频道助手对外信息：频道页「频道助手」面板展示昵称/头像/简介/开关状态。
     */
    @GetMapping("/assistant")
    public AiResponses.AssistantInfoResponse assistant(@PathVariable String channelId) {
        return settingsService.getInfo(channelId, currentUserProvider.requireUserId());
    }

    /**
     * 管理端读取频道助手设置（persona + 任务开关/频率）。
     */
    @GetMapping("/settings")
    public AiResponses.AssistantSettingsResponse settings(@PathVariable String channelId) {
        return settingsService.getSettings(channelId, currentUserProvider.requireUserId());
    }

    /**
     * 频道侧可选模型列表（频道主为助手指定模型用，只返回启用中的模型）。
     */
    @GetMapping("/models")
    public PageResult<AiResponses.SelectableModelResponse> models(@PathVariable String channelId) {
        return new PageResult<>(settingsService.listSelectableModels(channelId,
                currentUserProvider.requireUserId()), null);
    }

    /**
     * 管理端保存频道助手设置。
     */
    @PutMapping("/settings")
    public AiResponses.AssistantSettingsResponse saveSettings(@PathVariable String channelId,
                                                              @Valid @RequestBody AiRequests.AssistantSettingsRequest request) {
        return settingsService.saveSettings(channelId, request, currentUserProvider.requireUserId());
    }

    /**
     * 创建/修复助手虚拟账号（以「普通成员」身份加入频道，用于自动发帖评论点赞）。
     */
    @PostMapping("/assistant-account")
    public AiResponses.AssistantInfoResponse ensureAssistantAccount(@PathVariable String channelId) {
        return settingsService.ensureAssistantAccount(channelId, currentUserProvider.requireUserId());
    }

    /**
     * 自动任务执行日志（分页）。
     */
    @GetMapping("/task-logs")
    public AiResponses.TaskLogPage taskLogs(@PathVariable String channelId,
                                            @RequestParam(required = false) String taskType,
                                            @RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size) {
        return settingsService.listTaskLogs(channelId, currentUserProvider.requireUserId(), taskType, page, size);
    }

    /**
     * 手动触发一次任务（用于配置后试运行，仍受幂等与每日频率上限约束）。
     */
    @PostMapping("/tasks/run")
    public AiResponses.TaskLogResponse runTask(@PathVariable String channelId,
                                               @Valid @RequestBody AiRequests.RunTaskRequest request) {
        // 权限校验在 runTask 内通过 settingsService.findSetting 前先做：显式校验频道管理权限
        settingsService.getSettings(channelId, currentUserProvider.requireUserId());
        return taskService.runTask(channelId, request.taskType());
    }
}
