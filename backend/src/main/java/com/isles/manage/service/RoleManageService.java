package com.isles.manage.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.isles.channel.audit.AuditLogService;
import com.isles.channel.audit.ChannelAuditLog;
import com.isles.channel.entity.Board;
import com.isles.channel.entity.Channel;
import com.isles.channel.entity.ChannelMember;
import com.isles.channel.entity.Role;
import com.isles.channel.mapper.BoardMapper;
import com.isles.channel.mapper.ChannelMemberMapper;
import com.isles.channel.mapper.RoleMapper;
import com.isles.channel.service.ChannelAccessService;
import com.isles.channel.service.ChannelPermissions;
import com.isles.channel.service.SystemRoles;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.id.UlidGenerator;
import com.isles.common.json.JsonSupport;
import com.isles.common.time.ClockTimes;
import com.isles.manage.dto.CreateRoleRequest;
import com.isles.manage.dto.RoleResponse;
import com.isles.manage.dto.UpdateRoleRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 身份组管理（FR-ROLE-01/02/03）：列表、自定义身份组 CRUD、权限点分配。
 *
 * <ul>
 *   <li>四个系统身份组不可删除；系统身份组名称不可修改；</li>
 *   <li>频道主身份组不可编辑；</li>
 *   <li>非频道主只能创建/分配不超过自身权限点的身份组（docs/03 §2.3）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class RoleManageService {

    private static final Logger log = LoggerFactory.getLogger(RoleManageService.class);

    private final RoleMapper roleMapper;
    private final BoardMapper boardMapper;
    private final ChannelMemberMapper channelMemberMapper;
    private final ChannelAccessService accessService;
    private final AuditLogService auditLogService;
    private final JsonSupport jsonSupport;

    /**
     * 身份组列表（含成员数与板块范围名称）。
     */
    public List<RoleResponse> list(String channelId, String viewerId) {
        accessService.requireMemberContext(channelId, viewerId);
        List<Role> roles = roleMapper.selectList(Wrappers.<Role>lambdaQuery()
                .eq(Role::getChannelId, channelId)
                .orderByAsc(Role::getSort)
                .orderByAsc(Role::getCreatedAt));
        Map<String, Long> memberCounts = channelMemberMapper.selectRoleMemberCounts(channelId).stream()
                .collect(Collectors.toMap(row -> row.getRoleId(),
                        row -> row.getMemberCount() == null ? 0L : row.getMemberCount()));
        Map<String, Board> boards = loadBoards(roles);
        return roles.stream()
                .map(role -> toResponse(role, memberCounts.getOrDefault(role.getId(), 0L), boards))
                .toList();
    }

    /**
     * 新建自定义身份组（channel.role.manage）。
     */
    @Transactional
    public RoleResponse create(String channelId, CreateRoleRequest request, String actorId) {
        ChannelAccessService.ChannelAccessContext actorContext = accessService
                .requirePermission(channelId, actorId, ChannelPermissions.ROLE_MANAGE);
        String name = request.name().trim();
        requireUniqueName(channelId, name, null);
        List<String> permissions = normalizePermissions(request.permissions());
        requireGrantable(actorContext, permissions);
        String scopeBoardId = validateScopeBoard(channelId, request.scopeBoardId());

        Role role = new Role();
        role.setId(UlidGenerator.generate());
        role.setChannelId(channelId);
        role.setName(name);
        role.setDescription(request.description());
        role.setPermissions(jsonSupport.toJson(permissions));
        role.setScopeBoardId(scopeBoardId);
        role.setIsSystem(false);
        role.setSort(request.sort() == null ? nextSort(channelId) : request.sort());
        role.setCreatedAt(ClockTimes.nowUtc());
        role.setUpdatedAt(ClockTimes.nowUtc());
        roleMapper.insert(role);
        auditLogService.record(channelId, actorId, ChannelAuditLog.Action.ROLE_CREATE, "ROLE", role.getId(),
                Map.of("name", name, "permissions", permissions));
        log.info("创建身份组 channelId={} roleId={} actor={}", channelId, role.getId(), actorId);
        return toResponse(role, 0L, loadBoards(List.of(role)));
    }

    /**
     * 编辑身份组（system 身份组仅频道主可改权限，且名称不可改）。
     */
    @Transactional
    public RoleResponse update(String channelId, String roleId, UpdateRoleRequest request, String actorId) {
        ChannelAccessService.ChannelAccessContext actorContext = accessService
                .requirePermission(channelId, actorId, ChannelPermissions.ROLE_MANAGE);
        Channel channel = actorContext.channel();
        Role role = requireRole(channelId, roleId);
        boolean owner = accessService.isOwner(channel, actorId);
        if (SystemRoles.OWNER.equals(role.getName())) {
            throw new AppException(ErrorCode.SYSTEM_ROLE_PROTECTED, "频道主身份组不可修改");
        }
        if (Boolean.TRUE.equals(role.getIsSystem())) {
            if (!owner) {
                throw new AppException(ErrorCode.SYSTEM_ROLE_PROTECTED);
            }
            if (request.name() != null && !request.name().trim().equals(role.getName())) {
                throw new AppException(ErrorCode.SYSTEM_ROLE_PROTECTED, "系统身份组名称不可修改");
            }
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        if (request.name() != null) {
            String name = request.name().trim();
            if (!name.equals(role.getName())) {
                requireUniqueName(channelId, name, role.getId());
                detail.put("name", Map.of("before", role.getName(), "after", name));
                role.setName(name);
            }
        }
        if (request.description() != null) {
            role.setDescription(request.description());
            detail.put("description", request.description());
        }
        if (request.permissions() != null) {
            if (!owner) {
                // 不能编辑权限高于自身的身份组
                requireGrantable(actorContext, accessService.parsePermissions(role));
            }
            List<String> permissions = normalizePermissions(request.permissions());
            requireGrantable(actorContext, permissions);
            role.setPermissions(jsonSupport.toJson(permissions));
            detail.put("permissions", permissions);
        }
        if (request.scopeBoardId() != null) {
            role.setScopeBoardId(validateScopeBoard(channelId, request.scopeBoardId()));
            detail.put("scopeBoardId", role.getScopeBoardId());
        }
        if (request.sort() != null) {
            role.setSort(request.sort());
        }
        role.setUpdatedAt(ClockTimes.nowUtc());
        roleMapper.updateById(role);
        auditLogService.record(channelId, actorId, ChannelAuditLog.Action.ROLE_UPDATE, "ROLE", roleId, detail);
        log.info("更新身份组 channelId={} roleId={} actor={}", channelId, roleId, actorId);
        return toResponse(role, countMembers(roleId), loadBoards(List.of(role)));
    }

    /**
     * 删除自定义身份组：系统身份组不可删除；成员回落到普通成员身份组。
     */
    @Transactional
    public void delete(String channelId, String roleId, String actorId) {
        ChannelAccessService.ChannelAccessContext actorContext = accessService
                .requirePermission(channelId, actorId, ChannelPermissions.ROLE_MANAGE);
        Channel channel = actorContext.channel();
        Role role = requireRole(channelId, roleId);
        if (Boolean.TRUE.equals(role.getIsSystem()) || SystemRoles.OWNER.equals(role.getName())) {
            throw new AppException(ErrorCode.SYSTEM_ROLE_PROTECTED);
        }
        if (!accessService.isOwner(channel, actorId)) {
            requireGrantable(actorContext, accessService.parsePermissions(role));
        }
        Role memberRole = roleMapper.selectOne(Wrappers.<Role>lambdaQuery()
                .eq(Role::getChannelId, channelId)
                .eq(Role::getName, SystemRoles.MEMBER));
        if (memberRole == null) {
            throw new AppException(ErrorCode.INTERNAL_ERROR, "频道默认身份组缺失");
        }
        int moved = channelMemberMapper.update(null, Wrappers.<ChannelMember>lambdaUpdate()
                .eq(ChannelMember::getChannelId, channelId)
                .eq(ChannelMember::getRoleId, roleId)
                .set(ChannelMember::getRoleId, memberRole.getId())
                .set(ChannelMember::getUpdatedAt, ClockTimes.nowUtc()));
        roleMapper.deleteById(roleId);
        auditLogService.record(channelId, actorId, ChannelAuditLog.Action.ROLE_DELETE, "ROLE", roleId,
                Map.of("name", role.getName(), "movedMembers", moved));
        log.info("删除身份组 channelId={} roleId={} movedMembers={}", channelId, roleId, moved);
    }

    private void requireUniqueName(String channelId, String name, String excludeRoleId) {
        Long count = roleMapper.selectCount(Wrappers.<Role>lambdaQuery()
                .eq(Role::getChannelId, channelId)
                .eq(Role::getName, name)
                .ne(excludeRoleId != null, Role::getId, excludeRoleId));
        if (count != null && count > 0) {
            throw new AppException(ErrorCode.CONFLICT, "身份组名称已存在");
        }
    }

    private List<String> normalizePermissions(List<String> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String permission : permissions) {
            if (permission == null || !ChannelPermissions.ALL.contains(permission)) {
                throw new AppException(ErrorCode.VALIDATION_ERROR, "存在非法权限点: " + permission);
            }
            normalized.add(permission);
        }
        return new ArrayList<>(normalized);
    }

    /**
     * 非频道主只能授予自身拥有的权限点。
     */
    private void requireGrantable(ChannelAccessService.ChannelAccessContext actorContext, List<String> permissions) {
        if (accessService.isOwner(actorContext.channel(), actorContext.member().getUserId())) {
            return;
        }
        List<String> actorPermissions = accessService.parsePermissions(actorContext.role());
        if (!actorPermissions.containsAll(permissions)) {
            throw new AppException(ErrorCode.PERMISSION_EXCEEDED);
        }
    }

    private String validateScopeBoard(String channelId, String scopeBoardId) {
        if (scopeBoardId == null || scopeBoardId.isBlank()) {
            return null;
        }
        Board board = boardMapper.selectById(scopeBoardId);
        if (board == null || board.getDeletedAt() != null || !channelId.equals(board.getChannelId())) {
            throw new AppException(ErrorCode.BOARD_NOT_FOUND);
        }
        return scopeBoardId;
    }

    private Role requireRole(String channelId, String roleId) {
        Role role = roleMapper.selectById(roleId);
        if (role == null || !channelId.equals(role.getChannelId())) {
            throw new AppException(ErrorCode.ROLE_NOT_FOUND);
        }
        return role;
    }

    private int nextSort(String channelId) {
        Long count = roleMapper.selectCount(Wrappers.<Role>lambdaQuery().eq(Role::getChannelId, channelId));
        return count == null ? 0 : count.intValue();
    }

    private long countMembers(String roleId) {
        Long count = channelMemberMapper.selectCount(Wrappers.<ChannelMember>lambdaQuery()
                .eq(ChannelMember::getRoleId, roleId)
                .in(ChannelMember::getStatus, ChannelMember.Status.ACTIVE.name(),
                        ChannelMember.Status.MUTED.name()));
        return count == null ? 0 : count;
    }

    private Map<String, Board> loadBoards(List<Role> roles) {
        Set<String> boardIds = roles.stream()
                .map(Role::getScopeBoardId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (boardIds.isEmpty()) {
            return Map.of();
        }
        return boardMapper.selectBatchIds(boardIds).stream()
                .collect(Collectors.toMap(Board::getId, Function.identity()));
    }

    private RoleResponse toResponse(Role role, long memberCount, Map<String, Board> boards) {
        Board scopeBoard = role.getScopeBoardId() == null ? null : boards.get(role.getScopeBoardId());
        return new RoleResponse(
                role.getId(),
                role.getChannelId(),
                role.getName(),
                role.getDescription(),
                accessService.parsePermissions(role),
                role.getScopeBoardId(),
                scopeBoard == null ? null : scopeBoard.getName(),
                Boolean.TRUE.equals(role.getIsSystem()),
                role.getSort(),
                memberCount);
    }
}
