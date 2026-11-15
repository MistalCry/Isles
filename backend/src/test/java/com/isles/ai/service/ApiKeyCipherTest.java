package com.isles.ai.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AI API Key 加密器测试（docs/09 §3、docs/11 P1-8）：
 * 生产环境缺密钥必须拒绝启动，开发环境允许降级为明文并显式标记。
 */
class ApiKeyCipherTest {

    @Test
    void should_refuse_startup_when_secret_missing_in_production() {
        assertThatThrownBy(() -> new ApiKeyCipher("", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AI_API_KEY_SECRET");
    }

    @Test
    void should_allow_plaintext_fallback_in_development() {
        ApiKeyCipher cipher = new ApiKeyCipher("", false);

        assertThat(cipher.enabled()).isFalse();
        assertThat(cipher.encrypt("sk-test")).isEqualTo("sk-test");
    }

    @Test
    void should_encrypt_and_decrypt_with_configured_secret() {
        ApiKeyCipher cipher = new ApiKeyCipher("unit-test-secret", true);

        String encrypted = cipher.encrypt("sk-test");

        assertThat(cipher.enabled()).isTrue();
        assertThat(encrypted).startsWith("gcm:").isNotEqualTo("sk-test");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("sk-test");
    }

    @Test
    void should_keep_legacy_plaintext_readable() {
        ApiKeyCipher cipher = new ApiKeyCipher("unit-test-secret", true);

        assertThat(cipher.decrypt("sk-legacy-plain")).isEqualTo("sk-legacy-plain");
    }
}
