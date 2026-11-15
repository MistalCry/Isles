package com.isles.ai.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.isles.ai.config.AiProperties;
import com.isles.ai.dto.AiRequests;
import com.isles.ai.dto.AiResponses;
import com.isles.ai.entity.AiModelConfig;
import com.isles.ai.mapper.AiModelConfigMapper;
import com.isles.ai.provider.AiProviderFactory;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模型配置服务单元测试：未配置模型的状态提示、默认模型解析、API Key 加密与掩码返回。
 */
class AiModelConfigServiceTest {

    /**
     * MyBatis-Plus 的 Lambda 条件构造器依赖实体的 TableInfo 缓存；纯单元测试不启动 Spring 容器，
     * 需手动初始化，否则 {@code Wrappers.lambdaQuery()} 抛 "can not find lambda cache"。
     */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AiModelConfig.class);
    }

    private AiModelConfigMapper mapper;
    private ApiKeyCipher cipher;
    private AiModelConfigService service;

    @BeforeEach
    void setUp() {
        mapper = mock(AiModelConfigMapper.class);
        cipher = new ApiKeyCipher("unit-test-secret", false);
        AiProperties properties = AiProperties.defaults();
        service = new AiModelConfigService(mapper, new AiProviderFactory(cipher, properties), cipher, properties);
    }

    @Test
    void should_report_not_configured_when_no_enabled_model() {
        when(mapper.selectList(any())).thenReturn(List.of());

        AiResponses.AiStatusResponse status = service.status();

        assertThat(status.configured()).isFalse();
        assertThat(status.enabledModelCount()).isZero();
        assertThat(status.message()).contains("未配置");
    }

    @Test
    void should_throw_business_error_when_resolving_default_without_models() {
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.resolveDefault())
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.BUSINESS_RULE_VIOLATION));
    }

    @Test
    void should_reject_preferred_disabled_model() {
        AiModelConfig disabled = config("cfg-1", false, false);
        when(mapper.selectById("cfg-1")).thenReturn(disabled);

        assertThatThrownBy(() -> service.resolveForChat("cfg-1", null))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.BUSINESS_RULE_VIOLATION));
    }

    @Test
    void should_encrypt_api_key_and_return_mask_on_create() {
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.insert(any(AiModelConfig.class))).thenReturn(1);

        AiResponses.ModelResponse response = service.create(new AiRequests.ModelConfigRequest(
                "本地 LMStudio", "http://localhost:1234/v1", "sk-secret-123456", "qwen2.5-vl-7b",
                true, false, "CHAT_TEMPLATE", true, true, 60, 2048, true), "admin-1");

        assertThat(response.apiKeyConfigured()).isTrue();
        assertThat(response.apiKeyMasked()).isEqualTo("******");
        assertThat(response.isDefault()).isTrue();
        ArgumentCaptor<AiModelConfig> captor = ArgumentCaptor.forClass(AiModelConfig.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getApiKeyEncrypted()).isTrue();
        assertThat(captor.getValue().getApiKey()).startsWith("gcm:").doesNotContain("sk-secret-123456");
        assertThat(cipher.decrypt(captor.getValue().getApiKey())).isEqualTo("sk-secret-123456");
    }

    @Test
    void should_keep_existing_api_key_when_mask_submitted() {
        AiModelConfig existing = config("cfg-1", true, true);
        existing.setApiKey(cipher.encrypt("old-secret"));
        existing.setApiKeyEncrypted(true);
        when(mapper.selectById("cfg-1")).thenReturn(existing);
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.updateById(any(AiModelConfig.class))).thenReturn(1);

        service.update("cfg-1", new AiRequests.ModelConfigRequest(
                "本地 LMStudio", "http://localhost:1234/v1", "******", "qwen2.5-vl-7b",
                true, false, "CHAT_TEMPLATE", true, true, 60, 2048, false));

        assertThat(cipher.decrypt(existing.getApiKey())).isEqualTo("old-secret");
    }

    @Test
    void should_mask_plain_api_key_without_leaking_full_value() {
        AiModelConfig config = config("cfg-1", true, false);
        config.setApiKey("sk-1234567890");
        config.setApiKeyEncrypted(false);

        AiResponses.ModelResponse response = service.toResponse(config);

        assertThat(response.apiKeyMasked()).contains("******").doesNotContain("1234567890");
    }

    @Test
    void should_return_failure_when_connection_test_unreachable() {
        AiModelConfig config = config("cfg-1", true, false);
        config.setBaseUrl("http://127.0.0.1:1/v1");
        when(mapper.selectById("cfg-1")).thenReturn(config);

        AiResponses.ConnectionTestResponse response = service.testConnection("cfg-1");

        assertThat(response.success()).isFalse();
        assertThat(response.message()).isNotBlank();
    }

    private AiModelConfig config(String id, boolean enabled, boolean isDefault) {
        AiModelConfig config = new AiModelConfig();
        config.setId(id);
        config.setName("配置-" + id);
        config.setBaseUrl("http://localhost:1234/v1");
        config.setModel("test-model");
        config.setEnabled(enabled);
        config.setIsDefault(isDefault);
        config.setVisionEnabled(true);
        config.setReasoningEnabled(false);
        config.setTimeoutSeconds(30);
        config.setMaxTokens(1024);
        config.setApiKeyEncrypted(false);
        config.setCreatedAt(LocalDateTime.now());
        return config;
    }
}
