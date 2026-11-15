package com.isles.platform.setting;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开配置接口（docs/11 §5.1）：游客与前台页面读取站点信息、注册开关等。
 *
 * <p>只返回注册表中 {@code publicVisible=true} 的项；密钥、内部地址等敏感配置永不进入本接口。
 *
 * <p>缓存策略：{@code no-store}。后台保存配置要求「保存即生效」，若允许浏览器缓存，
 * 用户改完刷新仍是旧值（此前 60 秒延迟即由此产生）。本接口响应只有几百字节，不做客户端缓存。
 */
@RestController
@RequestMapping("/api/v1/platform")
@RequiredArgsConstructor
public class PlatformConfigController {

    private static final CacheControl NO_STORE = CacheControl.noStore().mustRevalidate();

    private final PlatformSettingService settingService;

    @GetMapping("/config")
    public ResponseEntity<PlatformSettingDtos.PublicConfigResponse> config() {
        return ResponseEntity.ok()
                .cacheControl(NO_STORE)
                .body(new PlatformSettingDtos.PublicConfigResponse(settingService.publicValues()));
    }
}
