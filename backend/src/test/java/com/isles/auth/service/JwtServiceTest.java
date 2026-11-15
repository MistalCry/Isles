package com.isles.auth.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JWT 密钥 fail-fast 测试（docs/09 §3、docs/11 P1-8）：缺失或过短必须拒绝启动，不允许使用默认密钥。
 */
class JwtServiceTest {

    private static final String VALID_SECRET = "unit-test-secret-0123456789abcdef0123456789abcdef";

    @Test
    void should_refuse_startup_when_secret_missing() {
        assertThatThrownBy(() -> new JwtService("", 15))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void should_refuse_startup_when_secret_too_short() {
        assertThatThrownBy(() -> new JwtService("too-short", 15))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }

    @Test
    void should_issue_and_parse_token_with_valid_secret() {
        JwtService jwtService = new JwtService(VALID_SECRET, 15);

        String token = jwtService.generateAccessToken("user-1", "alice");

        assertThat(token).isNotBlank();
        assertThat(jwtService.parseUserId(token)).isEqualTo("user-1");
    }
}
