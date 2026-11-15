package com.isles.ai.provider;

import com.isles.ai.config.AiProperties;
import com.isles.ai.entity.AiModelConfig;
import com.isles.ai.service.ApiKeyCipher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 根据数据库中的模型配置创建 {@link AiProvider}。
 *
 * <p>API Key 在调用前解密并只保存在 Provider 实例内，不进入日志与响应。
 */
@Component
@RequiredArgsConstructor
public class AiProviderFactory {

    private final ApiKeyCipher apiKeyCipher;
    private final AiProperties properties;

    public AiProvider create(AiModelConfig config) {
        String apiKey = config.getApiKey();
        if (apiKey != null && Boolean.TRUE.equals(config.getApiKeyEncrypted())) {
            apiKey = apiKeyCipher.decrypt(apiKey);
        }
        return createFromRaw(config.getBaseUrl(), apiKey, config.getTimeoutSeconds());
    }

    /**
     * 用原始连接参数创建 Provider（连通性测试、未保存配置测试用）。
     */
    public AiProvider createFromRaw(String baseUrl, String plainApiKey, Integer timeoutSeconds) {
        int timeout = timeoutSeconds == null || timeoutSeconds <= 0
                ? properties.defaultTimeoutSeconds()
                : timeoutSeconds;
        return new OpenAiCompatibleProvider(baseUrl, plainApiKey, timeout, properties.maxRetries());
    }
}
