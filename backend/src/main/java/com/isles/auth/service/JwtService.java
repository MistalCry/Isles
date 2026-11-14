package com.isles.auth.service;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * JWT 访问令牌服务：HS256 + Nimbus（spring-security-oauth2-jose），不引入 jjwt。
 *
 * <p>密钥只从环境变量 {@code JWT_SECRET} 注入（docs/07 §2）；缺失或长度不足 32 字节时启动失败（fail fast）。
 * access token 有效期默认 15 分钟，claims 含 sub/iat/exp/jti。
 */
@Service
public class JwtService {

    private static final int MIN_SECRET_BYTES = 32;

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final Duration accessTokenTtl;

    public JwtService(@Value("${app.jwt.secret:}") String secret,
                      @Value("${app.jwt.access-token-minutes:15}") long accessTokenMinutes) {
        if (secret == null || secret.isBlank()
                || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT_SECRET 未配置或长度不足 32 字节，拒绝启动");
        }
        SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.encoder = NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build();
        this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        this.accessTokenTtl = Duration.ofMinutes(accessTokenMinutes);
    }

    /**
     * 签发 access token。
     */
    public String generateAccessToken(String userId, String username) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userId)
                .issuedAt(now)
                .expiresAt(now.plus(accessTokenTtl))
                .id(com.isles.common.id.UlidGenerator.generate())
                .claim("username", username)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /**
     * 校验并解析用户 ID；签名错误、过期、格式非法均抛 {@link org.springframework.security.oauth2.jwt.JwtException}。
     */
    public String parseUserId(String token) {
        Jwt jwt = decoder.decode(token);
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new org.springframework.security.oauth2.jwt.JwtException("JWT 缺少 sub");
        }
        return subject;
    }

    public long accessTokenSeconds() {
        return accessTokenTtl.toSeconds();
    }

    /**
     * 暴露密钥源构造方式，便于测试与后续 RS256 扩展。
     */
    public static ImmutableSecret<SecurityContext> immutableSecret(SecretKey key) {
        return new ImmutableSecret<>(key);
    }
}
