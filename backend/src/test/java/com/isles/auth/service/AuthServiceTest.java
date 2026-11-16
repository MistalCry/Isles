package com.isles.auth.service;

import com.isles.auth.dto.LoginRequest;
import com.isles.auth.dto.RegisterRequest;
import com.isles.auth.entity.RefreshToken;
import com.isles.auth.mapper.RefreshTokenMapper;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.security.CurrentUserProvider;
import com.isles.platform.penalty.PenaltyService;
import com.isles.platform.setting.PlatformSettingService;
import com.isles.user.dto.UserResponse;
import com.isles.user.entity.User;
import com.isles.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 认证服务单元测试：注册重复用户名、登录失败、登录成功、刷新令牌轮换。
 *
 * <p>纯 Mockito 单测，不加载 Spring 上下文、不依赖 MySQL。
 */
class AuthServiceTest {

    private UserService userService;
    private RefreshTokenMapper refreshTokenMapper;
    private JwtService jwtService;
    private PasswordEncoder passwordEncoder;
    private PlatformSettingService platformSettingService;
    private PenaltyService penaltyService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        refreshTokenMapper = mock(RefreshTokenMapper.class);
        jwtService = mock(JwtService.class);
        passwordEncoder = mock(PasswordEncoder.class);
        platformSettingService = mock(PlatformSettingService.class);
        penaltyService = mock(PenaltyService.class);
        // 默认开放注册：单个用例按需覆盖
        when(platformSettingService.getBoolean("auth.register.enabled")).thenReturn(true);
        authService = new AuthService(userService, refreshTokenMapper, jwtService, passwordEncoder,
                mock(CurrentUserProvider.class), platformSettingService, penaltyService);
    }

    @Test
    void should_reject_register_when_platform_closed() {
        when(platformSettingService.getBoolean("auth.register.enabled")).thenReturn(false);

        assertThatThrownBy(() -> authService.register(new RegisterRequest("newbie", "S3cure-pass", null)))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.BUSINESS_RULE_VIOLATION));
        verify(userService, never()).createUser(anyString(), anyString(), any());
    }

    @Test
    void should_reject_register_when_username_taken() {
        when(userService.existsByUsername("vrfan")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(new RegisterRequest("vrfan", "S3cure-pass", null)))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.USERNAME_TAKEN));

        verify(userService, never()).createUser(anyString(), anyString(), any());
    }

    @Test
    void should_register_new_user_when_username_available() {
        User user = user(1L);
        UserResponse response = new UserResponse("user-1", "vrfan", "VR 爱好者", null, null, "UNKNOWN", null,
                Instant.now(), false);
        when(userService.existsByUsername("vrfan")).thenReturn(false);
        when(passwordEncoder.encode("S3cure-pass")).thenReturn("bcrypt-hash");
        when(userService.createUser("vrfan", "bcrypt-hash", "VR 爱好者")).thenReturn(user);
        when(userService.toResponse(user)).thenReturn(response);

        UserResponse result = authService.register(new RegisterRequest("vrfan", "S3cure-pass", "VR 爱好者"));

        assertThat(result.username()).isEqualTo("vrfan");
        assertThat(result.nickname()).isEqualTo("VR 爱好者");
    }

    @Test
    void should_reject_login_when_password_mismatch() {
        User user = user(1L);
        when(userService.findByUsername("vrfan")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-pass", "bcrypt-hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("vrfan", "wrong-pass"), "junit", "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_CREDENTIALS));

        verify(refreshTokenMapper, never()).insert(any(RefreshToken.class));
    }

    @Test
    void should_reject_login_when_account_banned() {
        User user = new User();
        user.setId("user-1");
        user.setUsername("vrfan");
        user.setPasswordHash("bcrypt-hash");
        user.setStatus(User.Status.BANNED.name());
        when(userService.findByUsername("vrfan")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("S3cure-pass", "bcrypt-hash")).thenReturn(true);
        when(penaltyService.isBanned("user-1")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(new LoginRequest("vrfan", "S3cure-pass"), "junit", "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.USER_BANNED));

        verify(refreshTokenMapper, never()).insert(any(RefreshToken.class));
    }

    @Test
    void should_issue_tokens_when_login_success() {
        User user = user(1L);
        UserResponse response = new UserResponse("user-1", "vrfan", "VR 爱好者", null, null, "UNKNOWN", null,
                Instant.now(), false);
        when(userService.findByUsername("vrfan")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("S3cure-pass", "bcrypt-hash")).thenReturn(true);
        when(jwtService.generateAccessToken("user-1", "vrfan")).thenReturn("access-token");
        when(jwtService.accessTokenSeconds()).thenReturn(900L);
        when(userService.toResponse(user)).thenReturn(response);
        when(refreshTokenMapper.insert(any(RefreshToken.class))).thenReturn(1);

        AuthService.LoginResult result = authService.login(new LoginRequest("vrfan", "S3cure-pass"), "junit",
                "127.0.0.1");

        assertThat(result.accessToken()).isEqualTo("access-token");
        assertThat(result.expiresInSeconds()).isEqualTo(900L);
        assertThat(result.refreshToken()).isNotBlank();
        assertThat(result.user().id()).isEqualTo("user-1");
        verify(refreshTokenMapper).insert(any(RefreshToken.class));
    }

    @Test
    void should_rotate_refresh_token_when_refresh_success() {
        User user = user(1L);
        RefreshToken stored = new RefreshToken();
        stored.setId("token-1");
        stored.setUserId("user-1");
        stored.setExpiresAt(LocalDateTime.now().plusDays(1));
        when(refreshTokenMapper.selectOne(any())).thenReturn(stored);
        when(userService.requireActiveById("user-1")).thenReturn(user);
        when(refreshTokenMapper.revokeById("token-1")).thenReturn(1);
        when(jwtService.generateAccessToken("user-1", "vrfan")).thenReturn("new-access-token");
        when(jwtService.accessTokenSeconds()).thenReturn(900L);
        when(refreshTokenMapper.insert(any(RefreshToken.class))).thenReturn(1);

        AuthService.RefreshResult result = authService.refresh("raw-refresh-token", "junit", "127.0.0.1");

        assertThat(result.accessToken()).isEqualTo("new-access-token");
        assertThat(result.refreshToken()).isNotBlank().isNotEqualTo("raw-refresh-token");
        verify(refreshTokenMapper).revokeById("token-1");
        verify(refreshTokenMapper).insert(any(RefreshToken.class));
    }

    private User user(Long seed) {
        User user = new User();
        user.setId("user-" + seed);
        user.setUsername("vrfan");
        user.setNickname("VR 爱好者");
        user.setPasswordHash("bcrypt-hash");
        user.setStatus(User.Status.ACTIVE.name());
        user.setCreatedAt(LocalDateTime.now());
        return user;
    }
}
