package com.isles.agent.service;

import com.isles.ai.entity.AiModelConfig;
import com.isles.ai.service.AiModelConfigService;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 代理可用模型解析（第 19 轮 P0-2）。
 *
 * <p>解析优先级（与频道助手一致，避免两套规则）：
 * <ol>
 *   <li>人设指定的 {@code agent_profiles.model_config_id}（且该模型 {@code enabled=1}）；</li>
 *   <li>平台默认（{@code ai_model_configs.is_default=1} 且启用）；</li>
 *   <li>首个启用模型；</li>
 *   <li>都没有 → {@code null}（调用方记 {@code SKIPPED}「未配置可用的 AI 模型」）。</li>
 * </ol>
 *
 * <p><b>降级不失败</b>：指定的模型被禁用/删除时回落到平台默认，并把
 * {@link #FALLBACK_NOTE} 交给调用方写进 {@code agent_runs.error}——历史日志要能解释
 * 「为什么这次用的不是配的那个模型」。
 */
@Service
@RequiredArgsConstructor
public class AgentModelService {

    private static final Logger log = LoggerFactory.getLogger(AgentModelService.class);

    /** 回落备注（写入 agent_runs.error，与最终失败原因用「；」拼接）。 */
    public static final String FALLBACK_NOTE = "指定模型不可用，已回落默认";

    private final AiModelConfigService modelConfigService;

    /**
     * 解析本次运行实际使用的模型。
     *
     * @param modelConfigId 人设指定的模型 id（可为 null = 跟随平台默认）
     * @return {@code config} 为 null 表示平台没有任何启用模型；{@code note} 非空表示发生过回落
     */
    public Choice resolve(String modelConfigId) {
        AiModelConfig specified = modelConfigService.findEnabled(modelConfigId);
        if (specified != null) {
            return new Choice(specified, null);
        }
        boolean askedForSpecific = modelConfigId != null && !modelConfigId.isBlank();
        if (askedForSpecific) {
            log.warn("AI 代理指定模型不可用，回落平台默认 agentModelConfigId={}", modelConfigId);
        }
        AiModelConfig fallback;
        try {
            fallback = modelConfigService.resolveDefault();
        } catch (AppException ex) {
            // 零启用模型：交给调用方记 SKIPPED，这里不抛
            fallback = null;
        }
        return new Choice(fallback, fallback == null ? null : (askedForSpecific ? FALLBACK_NOTE : null));
    }

    /**
     * 创建/更新人设时的校验：非空必须存在且 {@code enabled=1}，否则 422。
     *
     * @return 校验通过的配置（入参为空时返回 null = 跟随平台默认）
     */
    public AiModelConfig requireUsable(String modelConfigId) {
        if (modelConfigId == null || modelConfigId.isBlank()) {
            return null;
        }
        AiModelConfig config = modelConfigService.findEnabled(modelConfigId);
        if (config == null) {
            throw new AppException(ErrorCode.AGENT_MODEL_UNAVAILABLE,
                    "指定的 AI 模型不存在或已禁用：" + modelConfigId);
        }
        return config;
    }

    /**
     * 生效模型名（后台列表展示「当前用哪个模型」）：指定可用 → 指定名；否则默认模型名；无模型 → null。
     */
    public String effectiveName(String modelConfigId) {
        AiModelConfig config = resolve(modelConfigId).config();
        return config == null ? null : config.getName();
    }

    /**
     * @param config 实际使用的模型配置（null = 无可用模型）
     * @param note   回落备注（null = 未发生回落）
     */
    public record Choice(AiModelConfig config, String note) {
    }
}
