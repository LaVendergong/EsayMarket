package com.mall.core.service;

import com.mall.core.model.dto.RbacRoleDTO;
import com.mall.core.model.vo.AdminUserVO;
import com.mall.core.model.vo.PermissionVO;
import com.mall.core.model.vo.RoleVO;

import java.util.List;

/** 后台 RBAC 管理（角色、权限、管理员授权） */
public interface RbacService {

    List<RoleVO> roles();

    Long createRole(RbacRoleDTO dto);

    void updateRole(Long id, RbacRoleDTO dto);

    void deleteRole(Long id);

    void setRolePermissions(Long roleId, List<Long> permissionIds);

    List<PermissionVO> permissions();

    List<AdminUserVO> admins();

    void setUserRoles(Long userId, List<Long> roleIds);
}
