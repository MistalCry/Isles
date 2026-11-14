package com.isles.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 令牌相关工具：refresh token / 邀请 token 使用随机 256 bit，数据库只存 SHA-256 哈希。
 *
 * <p>数据库泄露时攻击者无法还原原始 token（docs/07 §2）。
 */
public final class TokenHasher {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32; // 256 bit

    private TokenHasher() {
    }

    /**
     * 生成 URL 安全的随机 token（Base64 URL 无填充，43 字符）。
     */
    public static String randomToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * SHA-256 十六进制小写哈希（64 字符，匹配数据库 CHAR(64)）。
     */
    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
