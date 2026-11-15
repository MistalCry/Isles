package com.isles.manage.dto;

import java.util.List;

/**
 * 身份组（含成员数），管理页与权限分配使用。
 */
public record RoleResponse(
        String id,
        String channelId,
        String name,
        String description,
        List<String> permissions,
        String scopeBoardId,
        String scopeBoardName,
        boolean system,
        Integer sort,
        long memberCount) {
}
