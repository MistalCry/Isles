package com.isles.agent.service;

import com.isles.ai.entity.AiModelConfig;
import com.isles.ai.service.AiModelConfigService;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 代理模型解析优先级（第 19 轮 P0-2）：
 * 人设指定（enabled=1）→ 平台默认 → 首个启用 → 都没有则 null（调用方记 SKIPPED）。
 */
class AgentModelServiceTest {

    private AiModelConfigService modelConfigService;
    private AgentModelService service;

    @BeforeEach
    void setUp() {
        modelConfigService = mock(AiModelConfigService.class);
        service = new AgentModelService(modelConfigService);
    }

    @Test
    void should_prefer_agent_model_when_enabled() {
        AiModelConfig specific = config("cfg-2", "Qwen 2.5 7B", "qwen2.5-7b");
        when(modelConfigService.findEnabled("cfg-2")).thenReturn(specific);

        AgentModelService.Choice choice = service.resolve("cfg-2");

        assertThat(choice.config()).isSameAs(specific);
        assertThat(choice.note()).isNull();
        assertThat(service.effectiveName("cfg-2")).isEqualTo("Qwen 2.5 7B");
    }

    @Test
    void should_follow_platform_default_when_no_agent_model() {
        AiModelConfig fallback = config("cfg-1", "平台默认", "gpt-4o-mini");
        when(modelConfigService.resolveDefault()).thenReturn(fallback);

        AgentModelService.Choice choice = service.resolve(null);

        assertThat(choice.config()).isSameAs(fallback);
        // 跟随默认不是「回落」，不该写备注
        assertThat(choice.note()).isNull();
    }

    @Test
    void should_fall_back_and_note_when_agent_model_unavailable() {
        AiModelConfig fallback = config("cfg-1", "平台默认", "gpt-4o-mini");
        when(modelConfigService.findEnabled("cfg-deleted")).thenReturn(null);
        when(modelConfigService.resolveDefault()).thenReturn(fallback);

        AgentModelService.Choice choice = service.resolve("cfg-deleted");

        assertThat(choice.config()).isSameAs(fallback);
        assertThat(choice.note()).isEqualTo(AgentModelService.FALLBACK_NOTE);
    }

    @Test
    void should_return_null_when_no_model_is_enabled_at_all() {
        when(modelConfigService.resolveDefault())
                .thenThrow(new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, "尚未配置可用的 AI 模型"));

        AgentModelService.Choice choice = service.resolve("cfg-2");

        assertThat(choice.config()).isNull();
        // 没有模型时原因由调用方统一写「未配置可用的 AI 模型」，不叠加回落备注
        assertThat(choice.note()).isNull();
        assertThat(service.effectiveName("cfg-2")).isNull();
    }

    @Test
    void should_reject_unusable_model_on_save() {
        when(modelConfigService.findEnabled("cfg-gone")).thenReturn(null);

        assertThatThrownBy(() -> service.requireUsable("cfg-gone"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AGENT_MODEL_UNAVAILABLE));
        // 422 是明确报错，不能静默存一个跑不起来的 id
        assertThat(ErrorCode.AGENT_MODEL_UNAVAILABLE.httpStatus().value()).isEqualTo(422);
    }

    @Test
    void should_accept_blank_model_as_follow_default() {
        assertThat(service.requireUsable(null)).isNull();
        assertThat(service.requireUsable("  ")).isNull();
    }

    private AiModelConfig config(String id, String name, String model) {
        AiModelConfig config = new AiModelConfig();
        config.setId(id);
        config.setName(name);
        config.setModel(model);
        config.setEnabled(true);
        return config;
    }
}
