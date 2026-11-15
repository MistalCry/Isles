package com.isles.ai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AI API Key 落库加密（AES-256-GCM）。
 *
 * <p>密钥来自环境变量 {@code app.ai.api-key-secret}（生产必须配置）。行为按环境区分：
 * <ul>
 *   <li>{@code app.security.require-secrets=true}（prod profile）：密钥缺失直接拒绝启动，禁止明文落库；</li>
 *   <li>本地/测试：密钥缺失时降级为明文并打 WARN，便于开发联调。</li>
 * </ul>
 * 无论是否加密，API Key 都不会出现在接口响应中。
 */
@Component
public class ApiKeyCipher {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyCipher.class);

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int AES_KEY_BITS = 256;
    private static final String PREFIX = "gcm:";

    private final SecretKeySpec key;
    private final SecureRandom secureRandom = new SecureRandom();

    public ApiKeyCipher(@Value("${app.ai.api-key-secret:}") String secret,
                        @Value("${app.security.require-secrets:false}") boolean requireSecrets) {
        if (secret == null || secret.isBlank()) {
            if (requireSecrets) {
                // 生产（profile=prod）必须配置密钥：明文落库属于安全事故，宁可启动失败
                throw new IllegalStateException(
                        "AI_API_KEY_SECRET 未配置：生产环境拒绝以明文存储 AI API Key，请注入 ≥32 字节随机值");
            }
            this.key = null;
            log.warn("app.ai.api-key-secret 未配置，AI API Key 将以明文存储（仅限本地开发，生产必须配置）");
            return;
        }
        this.key = new SecretKeySpec(sha256(secret), "AES");
    }

    public boolean enabled() {
        return key != null;
    }

    /**
     * 加密 API Key；未配置密钥时原样返回，由调用方标记未加密。
     */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty() || key == null) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (Exception ex) {
            // 加密失败不允许静默降级为明文入库
            throw new IllegalStateException("API Key 加密失败", ex);
        }
    }

    /**
     * 解密 API Key；兼容历史明文（无前缀）与解密失败（按原值返回，由上游鉴权失败暴露问题）。
     */
    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty() || !stored.startsWith(PREFIX)) {
            return stored;
        }
        if (key == null) {
            throw new IllegalStateException("API Key 为密文，但 app.ai.api-key-secret 未配置，无法解密");
        }
        try {
            byte[] payload = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(payload, 0, iv, 0, IV_LENGTH);
            byte[] cipherText = new byte[payload.length - IV_LENGTH];
            System.arraycopy(payload, IV_LENGTH, cipherText, 0, cipherText.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("API Key 解密失败（密钥可能已轮换）", ex);
        }
    }

    private static byte[] sha256(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
