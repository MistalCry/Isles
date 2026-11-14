package com.isles.config;

import com.isles.auth.security.JwtAuthenticationFilter;
import com.isles.common.error.ErrorCode;
import com.isles.common.web.JsonErrorWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * 安全配置（docs/07 §2/§3/§6）：
 * <ul>
 *   <li>无状态 API：JWT Header 鉴权，不使用服务端 Session；CSRF 由 SameSite=Lax + Header 鉴权控制；</li>
 *   <li>游客只读：公开频道/帖子/评论/搜索/板块 GET 接口 permitAll，写接口一律 authenticated；</li>
 *   <li>401/403 也返回统一错误信封（含 requestId）。</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final int BCRYPT_COST = 12;

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JsonErrorWriter jsonErrorWriter;
    private final String allowedOrigins;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          JsonErrorWriter jsonErrorWriter,
                          @Value("${app.cors.allowed-origins:http://localhost:5173}") String allowedOrigins) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.jsonErrorWriter = jsonErrorWriter;
        this.allowedOrigins = allowedOrigins;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_COST);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 健康检查与错误页
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/error").permitAll()
                        // 上传文件静态访问
                        .requestMatchers(HttpMethod.GET, "/uploads/**").permitAll()
                        // 认证入口
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login",
                                "/api/v1/auth/refresh").permitAll()
                        // 我的频道必须登录（先于 /channels/* 公开规则）
                        .requestMatchers("/api/v1/channels/mine").authenticated()
                        // 公开只读：用户资料/卡片
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/*", "/api/v1/users/*/card").permitAll()
                        // 公开只读：频道详情、板块、频道帖子流
                        .requestMatchers(HttpMethod.GET, "/api/v1/channels/*", "/api/v1/channels/*/boards",
                                "/api/v1/channels/*/posts").permitAll()
                        // 公开只读：帖子详情、评论列表
                        .requestMatchers(HttpMethod.GET, "/api/v1/posts/*", "/api/v1/posts/*/comments").permitAll()
                        // 公开只读：搜索
                        .requestMatchers(HttpMethod.GET, "/api/v1/search").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(this::handleUnauthorized)
                        .accessDeniedHandler((request, response, ex) -> jsonErrorWriter.write(request, response,
                                ErrorCode.FORBIDDEN)))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("X-Request-Id"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private void handleUnauthorized(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        jsonErrorWriter.write(request, response, ErrorCode.UNAUTHORIZED);
    }
}
