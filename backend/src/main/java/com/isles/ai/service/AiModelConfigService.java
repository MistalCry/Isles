package com.isles.ai.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.isles.ai.config.AiProperties;
import com.isles.ai.dto.AiRequests;
import com.isles.ai.dto.AiResponses;
import com.isles.ai.entity.AiModelConfig;
import com.isles.ai.mapper.AiModelConfigMapper;
import com.isles.ai.provider.AiProvider;
import com.isles.ai.provider.AiProviderException;
import com.isles.ai.provider.AiProviderFactory;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.id.UlidGenerator;
import com.isles.common.time.ClockTimes;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 模型配置服务（docs/03 FR-PADM-01 / FR-AIQA-10）：
 * 平台后台 CRUD、默认模型解析、连通性测试、模型列表。
 *
 * <p>安全：API Key 使用 {@link ApiKeyCipher} 加密落库，响应只返回掩码；
 * 更新时传空/掩码表示保持原密钥不变（避免前端回填密文）。
 */
@Service
@RequiredArgsConstructor
public class AiModelConfigService {

    private static final Logger log = LoggerFactory.getLogger(AiModelConfigService.class);

    private static final String MASKED_API_KEY = "******";
    private static final String DEFAULT_THINKING_MODE = "REASONING_EFFORT";
    private static final String NOT_CONFIGURED_MESSAGE =
            "尚未配置 AI 模型，请先到平台管理后台配置 LMStudio / OpenAI 兼容模型";

    private final AiModelConfigMapper modelConfigMapper;
    private final AiProviderFactory providerFactory;
    private final ApiKeyCipher apiKeyCipher;
    private final AiProperties properties;

    // ---------------------------------------------------------------------
    // 查询
    // ---------------------------------------------------------------------

    public List<AiModelConfig> listAll() {
        return modelConfigMapper.selectList(Wrappers.<AiModelConfig>lambdaQuery()
                .orderByDesc(AiModelConfig::getIsDefault)
                .orderByAsc(AiModelConfig::getCreatedAt)
                .orderByAsc(AiModelConfig::getId));
    }

    public List<AiModelConfig> listEnabled() {
        return modelConfigMapper.selectList(Wrappers.<AiModelConfig>lambdaQuery()
                .eq(AiModelConfig::getEnabled, true)
                .orderByDesc(AiModelConfig::getIsDefault)
                .orderByAsc(AiModelConfig::getCreatedAt)
                .orderByAsc(AiModelConfig::getId));
    }

    public List<AiResponses.ModelResponse> listEnabledResponses() {
        return listEnabled().stream().map(this::toResponse).toList();
    }

    public AiModelConfig requireById(String id) {
        AiModelConfig config = id == null ? null : modelConfigMapper.selectById(id);
        if (config == null) {
            throw new AppException(ErrorCode.RESOURCE_NOT_FOUND, "模型配置不存在");
        }
        return config;
    }

    /**
     * 解析一次对话使用的模型：优先请求指定，其次会话记忆，最后默认模型。
     */
    public AiModelConfig resolveForChat(String preferredId, String conversationPreferredId) {
        String candidateId = preferredId != null && !preferredId.isBlank()
                ? preferredId
                : conversationPreferredId;
        if (candidateId != null && !candidateId.isBlank()) {
            AiModelConfig config = modelConfigMapper.selectById(candidateId);
            if (config != null && Boolean.TRUE.equals(config.getEnabled())) {
                return config;
            }
            if (preferredId != null && !preferredId.isBlank()) {
                throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, "所选模型不可用，请重新选择");
            }
        }
        return resolveDefault();
    }

    /**
     * 默认模型：启用 + is_default 优先，否则取任一启用模型；都没有时返回明确业务错误。
     */
    public AiModelConfig resolveDefault() {
        AiModelConfig config = modelConfigMapper.selectOne(Wrappers.<AiModelConfig>lambdaQuery()
                .eq(AiModelConfig::getEnabled, true)
                .eq(AiModelConfig::getIsDefault, true)
                .last("LIMIT 1"));
        if (config != null) {
            return config;
        }
        List<AiModelConfig> enabled = listEnabled();
        if (enabled.isEmpty()) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, NOT_CONFIGURED_MESSAGE);
        }
        return enabled.get(0);
    }

    /**
     * AI 能力状态：前端进入频道问问时先查，未配置时展示明确引导而不是等到发送失败。
     */
    public AiResponses.AiStatusResponse status() {
        List<AiModelConfig> enabled = listEnabled();
        if (enabled.isEmpty()) {
            return new AiResponses.AiStatusResponse(false, 0, null, null, NOT_CONFIGURED_MESSAGE);
        }
        AiModelConfig defaultConfig = enabled.stream()
                .filter(item -> Boolean.TRUE.equals(item.getIsDefault()))
                .findFirst()
                .orElse(enabled.get(0));
        return new AiResponses.AiStatusResponse(true, enabled.size(), defaultConfig.getName(),
                defaultConfig.getModel(), "AI 能力已就绪");
    }

    public AiProvider providerFor(AiModelConfig config) {
        return providerFactory.create(config);
    }

    // ---------------------------------------------------------------------
    // 管理端 CRUD
    // ---------------------------------------------------------------------

    @Transactional
    public AiResponses.ModelResponse create(AiRequests.ModelConfigRequest request, String adminId) {
        ensureNameAvailable(request.name(), null);
        LocalDateTime now = ClockTimes.nowUtc();
        AiModelConfig config = new AiModelConfig();
        config.setId(UlidGenerator.generate());
        applyEditableFields(config, request);
        config.setCreatedBy(adminId);
        config.setCreatedAt(now);
        config.setUpdatedAt(now);
        if (Boolean.TRUE.equals(config.getIsDefault())) {
            clearOtherDefaults(config.getId());
        }
        modelConfigMapper.insert(config);
        log.info("AI 模型配置创建成功 modelConfigId={} name={}", config.getId(), config.getName());
        return toResponse(config);
    }

    /**
     * 幂等说明：按 id 更新；apiKey 为空/掩码时保持原值。
     */
    @Transactional
    public AiResponses.ModelResponse update(String id, AiRequests.ModelConfigRequest request) {
        AiModelConfig config = requireById(id);
        ensureNameAvailable(request.name(), id);
        applyEditableFields(config, request);
        config.setUpdatedAt(ClockTimes.nowUtc());
        if (Boolean.TRUE.equals(config.getIsDefault())) {
            clearOtherDefaults(config.getId());
        }
        modelConfigMapper.updateById(config);
        log.info("AI 模型配置更新成功 modelConfigId={}", id);
        return toResponse(config);
    }

    @Transactional
    public void delete(String id) {
        AiModelConfig config = requireById(id);
        modelConfigMapper.deleteById(config.getId());
        log.info("AI 模型配置删除成功 modelConfigId={}", id);
    }

    // ---------------------------------------------------------------------
    // 连通性测试
    // ---------------------------------------------------------------------

    /**
     * 用已保存配置测试连通性（GET /models）。
     */
    public AiResponses.ConnectionTestResponse testConnection(String id) {
        AiModelConfig config = requireById(id);
        return doTest(config.getBaseUrl(), config.getApiKey(), Boolean.TRUE.equals(config.getApiKeyEncrypted()),
                config.getTimeoutSeconds());
    }

    /**
     * 用未保存的配置测试连通性（管理后台「测试连接」按钮）。apiKey 为空时尝试复用同 Base URL 的已存密钥。
     */
    public AiResponses.ConnectionTestResponse testConnection(AiRequests.TestConnectionRequest request) {
        String apiKey = request.apiKey();
        boolean encrypted = false;
        if (apiKey == null || apiKey.isBlank()) {
            AiModelConfig existing = modelConfigMapper.selectOne(Wrappers.<AiModelConfig>lambdaQuery()
                    .eq(AiModelConfig::getBaseUrl, request.baseUrl().trim())
                    .last("LIMIT 1"));
            if (existing != null) {
                apiKey = existing.getApiKey();
                encrypted = Boolean.TRUE.equals(existing.getApiKeyEncrypted());
            }
        }
        return doTest(request.baseUrl(), apiKey, encrypted, properties.defaultTimeoutSeconds());
    }

    /**
     * 拉取上游模型列表（管理后台「模型列表」）。
     */
    public List<String> remoteModels(String id) {
        AiModelConfig config = requireById(id);
        try {
            return providerFor(config).listModels();
        } catch (AiProviderException ex) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, ex.getMessage());
        } catch (RuntimeException ex) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, "无法获取模型列表：" + ex.getMessage());
        }
    }

    private AiResponses.ConnectionTestResponse doTest(String baseUrl, String apiKey, boolean encrypted,
                                                      Integer timeoutSeconds) {
        verifyBaseUrl(baseUrl);
        String plainKey = apiKey;
        if (encrypted && apiKey != null) {
            plainKey = apiKeyCipher.decrypt(apiKey);
        }
        AiProvider provider = new AiProviderFactory(apiKeyCipher, properties)
                .createFromRaw(baseUrl, plainKey, timeoutSeconds);
        long started = System.currentTimeMillis();
        try {
            List<String> models = provider.listModels();
            return new AiResponses.ConnectionTestResponse(true, System.currentTimeMillis() - started, models,
                    "连接成功，发现 " + models.size() + " 个模型");
        } catch (AiProviderException ex) {
            return new AiResponses.ConnectionTestResponse(false, System.currentTimeMillis() - started, List.of(),
                    ex.getMessage());
        }
    }

    private void verifyBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "Base URL 不能为空");
        }
    }

    private void ensureNameAvailable(String name, String excludeId) {
        Long count = modelConfigMapper.selectCount(Wrappers.<AiModelConfig>lambdaQuery()
                .eq(AiModelConfig::getName, name.trim())
                .ne(excludeId != null, AiModelConfig::getId, excludeId));
        if (count != null && count > 0) {
            throw new AppException(ErrorCode.CONFLICT, "同名模型配置已存在");
        }
    }

    private void clearOtherDefaults(String keepId) {
        modelConfigMapper.update(null, Wrappers.<AiModelConfig>lambdaUpdate()
                .set(AiModelConfig::getIsDefault, false)
                .ne(AiModelConfig::getId, keepId));
    }

    private void applyEditableFields(AiModelConfig config, AiRequests.ModelConfigRequest request) {
        config.setName(request.name().trim());
        config.setBaseUrl(request.baseUrl().trim());
        config.setModel(request.model().trim());
        config.setVisionEnabled(request.visionEnabled());
        config.setReasoningEnabled(request.reasoningEnabled());
        config.setThinkingMode(request.thinkingMode() == null || request.thinkingMode().isBlank()
                ? DEFAULT_THINKING_MODE
                : request.thinkingMode());
        config.setIsDefault(request.isDefault());
        config.setEnabled(request.enabled());
        config.setTimeoutSeconds(request.timeoutSeconds() == null
                ? properties.defaultTimeoutSeconds()
                : request.timeoutSeconds());
        config.setMaxTokens(request.maxTokens() == null ? 2048 : request.maxTokens());
        String apiKey = request.apiKey();
        if (apiKey != null && !apiKey.isBlank() && !MASKED_API_KEY.equals(apiKey)) {
            config.setApiKey(apiKeyCipher.encrypt(apiKey));
            config.setApiKeyEncrypted(apiKeyCipher.enabled());
        } else if (config.getApiKey() == null && config.getApiKeyEncrypted() == null) {
            // 新建配置未提供密钥（本地 LMStudio 常无需鉴权）
            config.setApiKey(null);
            config.setApiKeyEncrypted(false);
        }
    }

    public AiResponses.ModelResponse toResponse(AiModelConfig config) {
        String masked = maskApiKey(config.getApiKey(), config.getApiKeyEncrypted());
        return new AiResponses.ModelResponse(
                config.getId(),
                config.getName(),
                config.getBaseUrl(),
                config.getModel(),
                Boolean.TRUE.equals(config.getVisionEnabled()),
                Boolean.TRUE.equals(config.getReasoningEnabled()),
                config.getThinkingMode() == null ? DEFAULT_THINKING_MODE : config.getThinkingMode(),
                Boolean.TRUE.equals(config.getIsDefault()),
                Boolean.TRUE.equals(config.getEnabled()),
                config.getTimeoutSeconds(),
                config.getMaxTokens(),
                config.getApiKey() != null && !config.getApiKey().isBlank(),
                masked,
                ClockTimes.toInstant(config.getCreatedAt()));
    }

    private String maskApiKey(String apiKey, Boolean encrypted) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        if (Boolean.TRUE.equals(encrypted)) {
            // 密文不展示任何片段
            return MASKED_API_KEY;
        }
        if (apiKey.length() <= 4) {
            return MASKED_API_KEY;
        }
        return apiKey.substring(0, 3) + MASKED_API_KEY + apiKey.substring(apiKey.length() - 2);
    }
}
