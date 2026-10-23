package com.mall.admin.controller;

import com.mall.common.result.R;
import com.mall.core.audit.AuditAction;
import com.mall.core.model.dto.RbacRoleDTO;
import com.mall.core.model.vo.AdminUserVO;
import com.mall.core.model.vo.PermissionVO;
import com.mall.core.model.vo.RoleVO;
import com.mall.core.service.AuditService;
import com.mall.core.service.RbacService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 角色与授权管理。
 *
 * <p>整个控制器要求 {@code system:rbac} 权限（只有超管默认拥有），
 * 否则「能改权限」就等于「能给自己提权」。
 */
@Tag(name = "后台-角色权限")
@RestController
@RequestMapping("/api/v1/admin/rbac")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('system:rbac')")
public class RbacController {

    private final RbacService rbacService;
    private final AuditService auditService;

    @Operation(summary = "角色列表（含已分配权限）")
    @GetMapping("/roles")
    public R<List<RoleVO>> roles() {
        return R.ok(rbacService.roles());
    }

    @Operation(summary = "新增角色")
    @PostMapping("/roles")
    public R<Long> createRole(@Valid @RequestBody RbacRoleDTO dto) {
        Long id = rbacService.createRole(dto);
        auditService.record("RBAC_ROLE_CREATE", "ROLE", id, "code=" + dto.getCode() + ", name=" + dto.getName());
        return R.ok(id);
    }

    @Operation(summary = "修改角色")
    @PutMapping("/roles/{id}")
    public R<Void> updateRole(@PathVariable Long id, @Valid @RequestBody RbacRoleDTO dto) {
        rbacService.updateRole(id, dto);
        auditService.record("RBAC_ROLE_UPDATE", "ROLE", id, "code=" + dto.getCode() + ", status=" + dto.getStatus());
        return R.ok();
    }

    @Operation(summary = "删除角色（内置角色和在用的角色不可删）")
    @DeleteMapping("/roles/{id}")
    public R<Void> deleteRole(@PathVariable Long id) {
        rbacService.deleteRole(id);
        auditService.record("RBAC_ROLE_DELETE", "ROLE", id, null);
        return R.ok();
    }

    @Operation(summary = "设置角色权限（全量覆盖）")
    @PutMapping("/roles/{id}/permissions")
    public R<Void> setRolePermissions(@PathVariable Long id, @RequestBody List<Long> permissionIds) {
        rbacService.setRolePermissions(id, permissionIds);
        auditService.record("RBAC_ROLE_PERM", "ROLE", id,
                "permissions=" + (permissionIds == null ? 0 : permissionIds.size()));
        return R.ok();
    }

    @Operation(summary = "全部权限点")
    @GetMapping("/permissions")
    public R<List<PermissionVO>> permissions() {
        return R.ok(rbacService.permissions());
    }

    @Operation(summary = "管理员列表（含角色）")
    @GetMapping("/admins")
    public R<List<AdminUserVO>> admins() {
        return R.ok(rbacService.admins());
    }

    @Operation(summary = "给管理员分配角色（全量覆盖，不能改自己）")
    @PutMapping("/admins/{userId}/roles")
    public R<Void> setUserRoles(@PathVariable Long userId, @RequestBody List<Long> roleIds) {
        rbacService.setUserRoles(userId, roleIds);
        auditService.record("RBAC_USER_ROLE", "USER", userId,
                "roles=" + (roleIds == null ? 0 : roleIds.size()));
        return R.ok();
    }
}
