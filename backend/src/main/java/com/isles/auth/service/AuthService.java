package com.isles.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.isles.auth.dto.LoginRequest;
import com.isles.auth.dto.RegisterRequest;
import com.isles.auth.entity.RefreshToken;
import com.isles.auth.mapper.RefreshTokenMapper;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.id.UlidGenerator;
import com.isles.common.security.CurrentUserProvider;
import com.isles.common.security.TokenHasher;
import com.isles.common.time.ClockTimes;
import com.isles.platform.penalty.PenaltyService;
import com.isles.platform.setting.PlatformSettingService;
import com.isles.user.dto.UserResponse;
import com.isles.user.entity.User;
import com.isles.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 认证应用服务：注册、登录、刷新（refresh token 轮换）、登出、当前用户。
 *
 * <p>安全要点（docs/07 §2）：
 * <ul>
 *   <li>密码 BCrypt cost 12，日志不打印密码/令牌；</li>
 *   <li>access token 15 分钟，refresh token 随机 256 bit、存 SHA-256、7 天、每次刷新轮换并撤销旧令牌；</li>
 *   <li>登录失败统一返回 401，不区分"用户不存在/密码错误"。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final int MAX_USER_AGENT_LENGTH = 255;
    private static final int MAX_IP_LENGTH = 45;

    private final UserService userService;
    private final RefreshTokenMapper refreshTokenMapper;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUserProvider currentUserProvider;
    private final PlatformSettingService platformSettingService;
    private final PenaltyService penaltyService;

    @Value("${app.auth.refresh-token-days:7}")
    private long refreshTokenDays;

    /**
     * 登录成功结果：refreshToken 明文只交给 Controller 写入 HttpOnly Cookie。
     */
    public record LoginResult(String accessToken, long expiresInSeconds, UserResponse user, String refreshToken) {
    }

    /**
     * 刷新结果：新旧 refresh token 轮换，明文只交给 Controller 写 Cookie。
     */
    public record RefreshResult(String accessToken, long expiresInSeconds, String refreshToken) {
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        // 平台运行时开关（docs/11 §5.2）：后台关闭注册后，注册接口立即拒绝
        if (!platformSettingService.getBoolean("auth.register.enabled")) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, "平台当前未开放注册");
        }
        String username = request.username().trim();
        if (userService.existsByUsername(username)) {
            throw new AppException(ErrorCode.USERNAME_TAKEN);
        }
        String passwordHash = passwordEncoder.encode(request.password());
        try {
            User user = userService.createUser(username, passwordHash, request.nickname());
            return userService.toResponse(user);
        } catch (DuplicateKeyException ex) {
            // 并发注册同一用户名：由唯一约束兜底，统一转成 409
            throw new AppException(ErrorCode.USERNAME_TAKEN);
        }
    }

    @Transactional
    public LoginResult login(LoginRequest request, String userAgent, String ip) {
        User user = userService.findByUsername(request.username().trim()).orElse(null);
        // 先校验凭据：用户不存在与密码错误返回同一错误，避免账号枚举
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            log.info("登录失败：凭据无效");
            throw new AppException(ErrorCode.INVALID_CREDENTIALS);
        }
        // 凭据正确后，封禁状态给出明确提示（处罚记录是事实来源，users.status 只是同步结果）
        if (penaltyService.isBanned(user.getId())) {
            log.info("登录失败：账号处于封禁状态 userId={}", user.getId());
            throw new AppException(ErrorCode.USER_BANNED);
        }
        if (!User.Status.ACTIVE.name().equals(user.getStatus())) {
            log.info("登录失败：账号状态不可用 userId={} status={}", user.getId(), user.getStatus());
            throw new AppException(ErrorCode.INVALID_CREDENTIALS);
        }
        String accessToken = jwtService.generateAccessToken(user.getId(), user.getUsername());
        String refreshToken = issueRefreshToken(user.getId(), userAgent, ip);
        log.info("登录成功 userId={}", user.getId());
        return new LoginResult(accessToken, jwtService.accessTokenSeconds(), userService.toResponse(user), refreshToken);
    }

    @Transactional
    public RefreshResult refresh(String rawRefreshToken, String userAgent, String ip) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new AppException(ErrorCode.UNAUTHORIZED, "缺少刷新令牌");
        }
        String tokenHash = TokenHasher.sha256Hex(rawRefreshToken);
        RefreshToken token = refreshTokenMapper.selectOne(Wrappers.<RefreshToken>lambdaQuery()
                .eq(RefreshToken::getTokenHash, tokenHash));
        if (token == null || token.getRevokedAt() != null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        if (!token.getExpiresAt().isAfter(ClockTimes.nowUtc())) {
            throw new AppException(ErrorCode.TOKEN_EXPIRED);
        }
        User user = findActiveUser(token.getUserId());
        // 轮换：旧令牌立即撤销；撤销失败的并发请求按未登录处理
        int revoked = refreshTokenMapper.revokeById(token.getId());
        if (revoked == 0) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        String accessToken = jwtService.generateAccessToken(user.getId(), user.getUsername());
        String newRefreshToken = issueRefreshToken(user.getId(), userAgent, ip);
        log.info("刷新令牌成功 userId={}", user.getId());
        return new RefreshResult(accessToken, jwtService.accessTokenSeconds(), newRefreshToken);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        RefreshToken token = refreshTokenMapper.selectOne(Wrappers.<RefreshToken>lambdaQuery()
                .eq(RefreshToken::getTokenHash, TokenHasher.sha256Hex(rawRefreshToken)));
        if (token != null && token.getRevokedAt() == null) {
            refreshTokenMapper.revokeById(token.getId());
            log.info("登出成功 userId={}", token.getUserId());
        }
    }

    public UserResponse me() {
        return userService.getPublicUser(currentUserProvider.requireUserId());
    }

    private User findActiveUser(String userId) {
        try {
            return userService.requireActiveById(userId);
        } catch (AppException ex) {
            // 刷新令牌指向的账号已不可用：按未登录处理，避免泄露账号状态
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
    }

    private String issueRefreshToken(String userId, String userAgent, String ip) {
        String rawToken = TokenHasher.randomToken();
        LocalDateTime now = ClockTimes.nowUtc();
        RefreshToken token = new RefreshToken();
        token.setId(UlidGenerator.generate());
        token.setUserId(userId);
        token.setTokenHash(TokenHasher.sha256Hex(rawToken));
        token.setExpiresAt(now.plusDays(refreshTokenDays));
        token.setUserAgent(truncate(userAgent, MAX_USER_AGENT_LENGTH));
        token.setIp(truncate(ip, MAX_IP_LENGTH));
        token.setCreatedAt(now);
        refreshTokenMapper.insert(token);
        return rawToken;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
