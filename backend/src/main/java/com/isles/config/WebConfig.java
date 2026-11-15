package com.isles.config;

import com.isles.common.ratelimit.RateLimitInterceptor;
import com.isles.platform.penalty.PlatformMuteInterceptor;
import com.isles.platform.permission.PlatformPermissionInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Web 配置：把上传目录以静态资源方式暴露在 {@code /uploads/**}（生产由 Nginx 承担，见 docs/07 §5），
 * 并注册接口限流拦截器（docs/07 §7）。
 *
 * <p>上传目录不在 Web 根、文件名随机化，静态资源仅读。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebConfig.class);

    private final String uploadDir;
    private final RateLimitInterceptor rateLimitInterceptor;
    private final PlatformPermissionInterceptor platformPermissionInterceptor;
    private final PlatformMuteInterceptor platformMuteInterceptor;

    public WebConfig(@Value("${app.upload-dir:./uploads}") String uploadDir,
                     RateLimitInterceptor rateLimitInterceptor,
                     PlatformPermissionInterceptor platformPermissionInterceptor,
                     PlatformMuteInterceptor platformMuteInterceptor) {
        this.uploadDir = uploadDir;
        this.rateLimitInterceptor = rateLimitInterceptor;
        this.platformPermissionInterceptor = platformPermissionInterceptor;
        this.platformMuteInterceptor = platformMuteInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 平台权限先于限流：无权限的请求不消耗配额；/admin/me 供任意登录用户判断是否展示后台入口
        registry.addInterceptor(platformPermissionInterceptor)
                .addPathPatterns("/api/v1/admin/**")
                .excludePathPatterns("/api/v1/admin/me")
                .order(0);
        // 全站禁言：只拦内容生产接口，未登录请求交给安全链返回 401
        registry.addInterceptor(platformMuteInterceptor)
                .addPathPatterns("/api/v1/**")
                .order(1);
        registry.addInterceptor(rateLimitInterceptor).addPathPatterns("/api/**").order(2);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException ex) {
            throw new IllegalStateException("无法创建上传目录: " + root, ex);
        }
        String location = root.toUri().toString();
        if (!location.endsWith("/")) {
            location = location + "/";
        }
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(location)
                .setCachePeriod(3600);
        log.info("上传目录已配置 uploadDir={} urlPrefix=/uploads/**", root);
    }
}
