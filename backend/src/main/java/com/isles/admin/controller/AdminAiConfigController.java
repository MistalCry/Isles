package com.isles.admin.controller;

import com.isles.admin.service.AdminAuthService;
import com.isles.ai.dto.AiRequests;
import com.isles.ai.dto.AiResponses;
import com.isles.ai.service.AiModelConfigService;
import com.isles.common.pagination.PageResult;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台后台 AI 配置接口（docs/03 FR-PADM-01）：LMStudio / OpenAI 兼容模型配置 CRUD、连通性测试、模型列表。
 *
 * <p>仅平台管理员可访问；API Key 只写不读（响应只返回掩码与是否已配置）。
 */
@RestController
@RequestMapping("/api/v1/admin/ai")
@RequiredArgsConstructor
public class AdminAiConfigController {

    private final AdminAuthService adminAuthService;
    private final AiModelConfigService modelConfigService;

    @GetMapping("/models")
    public PageResult<AiResponses.ModelResponse> models() {
        adminAuthService.requireAdmin();
        return new PageResult<>(modelConfigService.listAll().stream()
                .map(modelConfigService::toResponse)
                .toList(), null);
    }

    @PostMapping("/models")
    @ResponseStatus(HttpStatus.CREATED)
    public AiResponses.ModelResponse create(@Valid @RequestBody AiRequests.ModelConfigRequest request) {
        String adminId = adminAuthService.requireAdmin();
        return modelConfigService.create(request, adminId);
    }

    @PutMapping("/models/{id}")
    public AiResponses.ModelResponse update(@PathVariable String id,
                                            @Valid @RequestBody AiRequests.ModelConfigRequest request) {
        adminAuthService.requireAdmin();
        return modelConfigService.update(id, request);
    }

    @DeleteMapping("/models/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        adminAuthService.requireAdmin();
        modelConfigService.delete(id);
    }

    /**
     * 用已保存配置测试连通性（拉取 /models 列表）。
     */
    @PostMapping("/models/{id}/test")
    public AiResponses.ConnectionTestResponse test(@PathVariable String id) {
        adminAuthService.requireAdmin();
        return modelConfigService.testConnection(id);
    }

    /**
     * 用未保存配置测试连通性（表单「测试连接」）。
     */
    @PostMapping("/test-connection")
    public AiResponses.ConnectionTestResponse testConnection(
            @Valid @RequestBody AiRequests.TestConnectionRequest request) {
        adminAuthService.requireAdmin();
        return modelConfigService.testConnection(request);
    }

    /**
     * 拉取上游模型列表，便于管理后台选择模型 ID。
     */
    @GetMapping("/models/{id}/remote-models")
    public PageResult<String> remoteModels(@PathVariable String id) {
        adminAuthService.requireAdmin();
        return new PageResult<>(modelConfigService.remoteModels(id), null);
    }

    @GetMapping("/status")
    public AiResponses.AiStatusResponse status() {
        adminAuthService.requireAdmin();
        return modelConfigService.status();
    }
}
