package com.isles.platform.setting;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * 公开配置接口（docs/11 §5.1）：游客与前台页面读取站点信息、注册开关等。
 *
 * <p>只返回注册表中 {@code publicVisible=true} 的项；密钥、内部地址等敏感配置永不进入本接口。
 * 允许客户端缓存 60 秒，后台修改后最迟 1 分钟在前台生效。
 */
@RestController
@RequestMapping("/api/v1/platform")
@RequiredArgsConstructor
public class PlatformConfigController {

    private static final CacheControl PUBLIC_CACHE = CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic();

    private final PlatformSettingService settingService;

    @GetMapping("/config")
    public ResponseEntity<PlatformSettingDtos.PublicConfigResponse> config() {
        return ResponseEntity.ok()
                .cacheControl(PUBLIC_CACHE)
                .body(new PlatformSettingDtos.PublicConfigResponse(settingService.publicValues()));
    }
}
