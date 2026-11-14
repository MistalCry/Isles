package com.isles.common.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 令牌哈希工具单元测试：数据库只存 64 位十六进制 SHA-256，原始 token 不可还原。
 */
class TokenHasherTest {

    @Test
    void should_generate_url_safe_token_with_256_bits() {
        String token = TokenHasher.randomToken();

        assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]{43}");
        assertThat(TokenHasher.randomToken()).isNotEqualTo(token);
    }

    @Test
    void should_hash_token_with_sha256_hex() {
        String hash = TokenHasher.sha256Hex("isles");

        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(hash).isEqualTo(TokenHasher.sha256Hex("isles"));
        assertThat(hash).isNotEqualTo(TokenHasher.sha256Hex("isles2"));
    }
}
