package com.isles.config;

import com.isles.auth.security.JwtAuthenticationFilter;
import com.isles.common.web.JsonErrorWriter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * CORS 白名单回归测试。
 *
 * <p>背景：浏览器经 Vite 代理访问后端时，代理会把 Origin 原样透传（Host 被改写），
 * 后端因此把「同源」请求当成跨域校验。若只放行 {@code http://localhost:5173}，
 * 用户用 {@code http://127.0.0.1:5173} 打开页面时所有接口都会返回
 * {@code 403 Invalid CORS request}（前端表现为「无法连接服务器」）。
 */
class CorsConfigTest {

    private CorsConfiguration corsConfig(String allowedOrigins) {
        SecurityConfig config = new SecurityConfig(
                mock(JwtAuthenticationFilter.class), mock(JsonErrorWriter.class), allowedOrigins);
        CorsConfigurationSource source = config.corsConfigurationSource();
        return source.getCorsConfiguration(new MockHttpServletRequest("POST", "/api/v1/auth/register"));
    }

    @Test
    void should_allow_localhost_and_loopback_ip_for_local_dev() {
        CorsConfiguration configuration = corsConfig(
                "http://localhost:5173,http://127.0.0.1:5173,http://localhost:*,http://127.0.0.1:*");

        assertThat(configuration).isNotNull();
        assertThat(configuration.checkOrigin("http://localhost:5173")).isEqualTo("http://localhost:5173");
        assertThat(configuration.checkOrigin("http://127.0.0.1:5173")).isEqualTo("http://127.0.0.1:5173");
        // 换端口（isles.bat start 8082 5174 这类用法）也应放行
        assertThat(configuration.checkOrigin("http://127.0.0.1:5174")).isEqualTo("http://127.0.0.1:5174");
    }

    @Test
    void should_reject_foreign_origin() {
        CorsConfiguration configuration = corsConfig("http://localhost:5173,http://127.0.0.1:5173");

        assertThat(configuration.checkOrigin("https://evil.example.com")).isNull();
        // 前缀相同但主机不同的域名不能被通配符误放行
        assertThat(configuration.checkOrigin("http://127.0.0.1.evil.com")).isNull();
    }

    @Test
    void should_separate_wildcard_patterns_from_exact_origins() {
        // setAllowedOrigins 不接受通配符，配置类必须把含 * 的条目拆到 allowedOriginPatterns
        CorsConfiguration configuration = corsConfig("https://isles.example.com,http://localhost:*");

        assertThat(configuration.getAllowedOrigins()).containsExactly("https://isles.example.com");
        assertThat(configuration.getAllowedOriginPatterns()).containsExactly("http://localhost:*");
        assertThat(configuration.checkOrigin("http://localhost:6000")).isEqualTo("http://localhost:6000");
    }
}
