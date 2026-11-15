package com.isles.admin.controller;

import com.isles.common.security.CurrentUserProvider;
import com.isles.platform.permission.PlatformPermission;
import com.isles.platform.permission.RequirePlatformPermission;
import com.isles.platform.setting.PlatformSettingDtos;
import com.isles.platform.setting.PlatformSettingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台配置中心接口（docs/11 §5.2）。
 *
 * <p>配置项元数据由代码注册表提供，前端按 {@code type} 渲染控件；写入即生效（无需重启）。
 * 权限校验由 {@code PlatformPermissionInterceptor} 依据注解完成。
 */
@RestController
@RequestMapping("/api/v1/admin/config")
@RequiredArgsConstructor
public class AdminConfigController {

    private final PlatformSettingService settingService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping
    @RequirePlatformPermission(PlatformPermission.CONFIG_READ)
    public PlatformSettingDtos.SettingListView list() {
        return settingService.list();
    }

    @PutMapping("/{key}")
    @RequirePlatformPermission(PlatformPermission.CONFIG_WRITE)
    public PlatformSettingDtos.SettingItem update(
            @PathVariable String key,
            @Valid @RequestBody PlatformSettingDtos.UpdateSettingRequest request) {
        return settingService.update(key, request.value(), currentUserProvider.requireUserId());
    }

    @PutMapping
    @RequirePlatformPermission(PlatformPermission.CONFIG_WRITE)
    public List<PlatformSettingDtos.SettingItem> updateBatch(
            @Valid @RequestBody PlatformSettingDtos.BatchUpdateRequest request) {
        return settingService.updateBatch(request.items(), currentUserProvider.requireUserId());
    }

    /**
     * 恢复默认值（删除覆盖行，保留历史）。
     */
    @PostMapping("/{key}/reset")
    @RequirePlatformPermission(PlatformPermission.CONFIG_WRITE)
    public PlatformSettingDtos.SettingItem reset(@PathVariable String key) {
        return settingService.reset(key, currentUserProvider.requireUserId());
    }

    @GetMapping("/history")
    @RequirePlatformPermission(PlatformPermission.CONFIG_READ)
    public PlatformSettingDtos.HistoryPage history(@RequestParam(required = false) String key,
                                                   @RequestParam(required = false) Integer page,
                                                   @RequestParam(required = false) Integer size) {
        return settingService.history(key, page, size);
    }
}
