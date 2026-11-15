package com.isles.ai.controller;

import com.isles.ai.dto.AiResponses;
import com.isles.ai.service.AiModelConfigService;
import com.isles.common.pagination.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 通用能力接口（docs/03 FR-AIQA-02/10）：
 * 能力状态（是否已配置模型）与用户可选模型列表。
 */
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiModelConfigService modelConfigService;

    /**
     * AI 能力状态：前端进入频道问问先查询，未配置模型时展示明确引导。
     */
    @GetMapping("/status")
    public AiResponses.AiStatusResponse status() {
        return modelConfigService.status();
    }

    /**
     * 可用模型列表（只返回启用配置，不含 API Key）。
     */
    @GetMapping("/models")
    public PageResult<AiResponses.ModelResponse> models() {
        return new PageResult<>(modelConfigService.listEnabledResponses(), null);
    }
}
