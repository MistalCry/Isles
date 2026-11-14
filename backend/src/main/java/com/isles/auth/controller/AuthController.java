package com.isles.auth.controller;

import com.isles.auth.dto.LoginRequest;
import com.isles.auth.dto.LoginResponse;
import com.isles.auth.dto.RefreshResponse;
import com.isles.auth.dto.RegisterRequest;
import com.isles.auth.service.AuthService;
import com.isles.user.dto.UserResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * 认证接口（docs/05 §2.1）：注册/登录/刷新/登出/当前用户。
 *
 * <p>refresh token 只通过 HttpOnly + SameSite=Lax Cookie 传输（docs/07 §2），不进入响应体；
 * access token 返回给前端存内存。
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String REFRESH_COOKIE = "refresh_token";
    private static final String REFRESH_COOKIE_PATH = "/api/v1/auth";
    private static final String TOKEN_TYPE = "Bearer";

    private final AuthService authService;

    @Value("${app.auth.cookie-secure:false}")
    private boolean cookieSecure;

    @Value("${app.auth.cookie-same-site:Lax}")
    private String cookieSameSite;

    @Value("${app.auth.refresh-token-days:7}")
    private long refreshTokenDays;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest,
                               HttpServletResponse httpResponse) {
        AuthService.LoginResult result = authService.login(request, httpRequest.getHeader(HttpHeaders.USER_AGENT),
                httpRequest.getRemoteAddr());
        writeRefreshCookie(httpResponse, result.refreshToken(), Duration.ofDays(refreshTokenDays));
        return new LoginResponse(result.accessToken(), TOKEN_TYPE, result.expiresInSeconds(), result.user());
    }

    @PostMapping("/refresh")
    public RefreshResponse refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String rawRefreshToken,
                                   HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        AuthService.RefreshResult result = authService.refresh(rawRefreshToken,
                httpRequest.getHeader(HttpHeaders.USER_AGENT), httpRequest.getRemoteAddr());
        writeRefreshCookie(httpResponse, result.refreshToken(), Duration.ofDays(refreshTokenDays));
        return new RefreshResponse(result.accessToken(), TOKEN_TYPE, result.expiresInSeconds());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@CookieValue(name = REFRESH_COOKIE, required = false) String rawRefreshToken,
                       HttpServletResponse httpResponse) {
        authService.logout(rawRefreshToken);
        writeRefreshCookie(httpResponse, "", Duration.ZERO);
    }

    @GetMapping("/me")
    public UserResponse me() {
        return authService.me();
    }

    private void writeRefreshCookie(HttpServletResponse response, String value, Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite(cookieSameSite)
                .path(REFRESH_COOKIE_PATH)
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
