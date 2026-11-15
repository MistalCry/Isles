package com.isles.manage.controller;

import com.isles.common.pagination.PageResult;
import com.isles.common.security.CurrentUserProvider;
import com.isles.manage.dto.CreateRoleRequest;
import com.isles.manage.dto.RoleResponse;
import com.isles.manage.dto.UpdateRoleRequest;
import com.isles.manage.service.RoleManageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 身份组管理（FR-ROLE-01/02/03）。
 */
@RestController
@RequestMapping("/api/v1/channels")
@RequiredArgsConstructor
public class RoleManageController {

    private final RoleManageService roleManageService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping("/{id}/roles")
    public PageResult<RoleResponse> listRoles(@PathVariable String id) {
        return new PageResult<>(roleManageService.list(id, currentUserProvider.requireUserId()), null);
    }

    @PostMapping("/{id}/roles")
    @ResponseStatus(HttpStatus.CREATED)
    public RoleResponse createRole(@PathVariable String id, @Valid @RequestBody CreateRoleRequest request) {
        return roleManageService.create(id, request, currentUserProvider.requireUserId());
    }

    @PatchMapping("/{id}/roles/{roleId}")
    public RoleResponse updateRole(@PathVariable String id, @PathVariable String roleId,
                                   @Valid @RequestBody UpdateRoleRequest request) {
        return roleManageService.update(id, roleId, request, currentUserProvider.requireUserId());
    }

    @DeleteMapping("/{id}/roles/{roleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRole(@PathVariable String id, @PathVariable String roleId) {
        roleManageService.delete(id, roleId, currentUserProvider.requireUserId());
    }
}
