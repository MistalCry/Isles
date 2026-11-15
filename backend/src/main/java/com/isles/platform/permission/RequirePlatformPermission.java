package com.isles.platform.permission;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明接口所需的平台权限点（docs/11 §7.2）。
 *
 * <p>由 {@link PlatformPermissionInterceptor} 在 {@code preHandle} 校验；未标注的
 * {@code /api/v1/admin/**} 接口默认要求「任意平台角色」，避免新增接口漏校验。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePlatformPermission {

    PlatformPermission value();
}
