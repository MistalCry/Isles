package com.isles.platform.setting;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 公开配置接口缓存策略测试：必须禁止客户端缓存，否则后台保存后前台仍读旧值。
 */
class PlatformConfigControllerTest {

    @Test
    void should_forbid_client_caching() {
        PlatformSettingService settingService = mock(PlatformSettingService.class);
        when(settingService.publicValues()).thenReturn(Map.of("site.name", "群岛"));
        PlatformConfigController controller = new PlatformConfigController(settingService);

        ResponseEntity<PlatformSettingDtos.PublicConfigResponse> response = controller.config();

        String cacheControl = response.getHeaders().getCacheControl();
        assertThat(cacheControl).isNotNull();
        assertThat(cacheControl).contains("no-store");
        assertThat(cacheControl).doesNotContain("max-age=60");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().values()).containsEntry("site.name", "群岛");
    }
}
