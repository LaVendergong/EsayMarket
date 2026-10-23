package com.mall.core.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.common.exception.BusinessException;
import com.mall.common.result.ResultCode;
import com.mall.core.entity.AdminPermission;
import com.mall.core.entity.AdminRole;
import com.mall.core.entity.User;
import com.mall.core.mapper.AdminPermissionMapper;
import com.mall.core.mapper.AdminRbacMapper;
import com.mall.core.mapper.AdminRoleMapper;
import com.mall.core.mapper.UserMapper;
import com.mall.core.model.dto.RbacRoleDTO;
import com.mall.core.model.vo.AdminUserVO;
import com.mall.core.model.vo.PermissionVO;
import com.mall.core.model.vo.RoleVO;
import com.mall.core.security.UserContext;
import com.mall.core.service.AdminPermissionService;
import com.mall.core.service.RbacService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RbacServiceImpl implements RbacService {

    private final AdminRoleMapper roleMapper;
    private final AdminPermissionMapper permissionMapper;
    private final AdminRbacMapper rbacMapper;
    private final UserMapper userMapper;
    private final AdminPermissionService adminPermissionService;

    @Override
    public List<RoleVO> roles() {
        return roleMapper.selectList(Wrappers.<AdminRole>lambdaQuery().orderByAsc(AdminRole::getId))
                .stream().map(r -> {
                    RoleVO vo = new RoleVO();
                    vo.setId(r.getId());
                    vo.setCode(r.getCode());
                    vo.setName(r.getName());
                    vo.setDescription(r.getDescription());
                    vo.setBuiltIn(r.getBuiltIn());
                    vo.setStatus(r.getStatus());
                    // 超管的权限是代码里短路的，不展示关联（展示会让人误以为可以改）
                    vo.setPermissionIds(AdminPermissionServiceImpl.SUPER_ADMIN.equals(r.getCode())
                            ? List.of() : rbacMapper.permissionIdsOf(r.getId()));
                    return vo;
                }).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createRole(RbacRoleDTO dto) {
        Long exists = roleMapper.selectCount(Wrappers.<AdminRole>lambdaQuery().eq(AdminRole::getCode, dto.getCode()));
        if (exists != null && exists > 0) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "角色标识已存在");
        }
        AdminRole role = new AdminRole();
        role.setCode(dto.getCode());
        role.setName(dto.getName());
        role.setDescription(dto.getDescription());
        role.setBuiltIn(0);
        role.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
        roleMapper.insert(role);
        return role.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateRole(Long id, RbacRoleDTO dto) {
        AdminRole role = requireRole(id);
        if (Objects.equals(role.getBuiltIn(), 1) && !Objects.equals(role.getCode(), dto.getCode())) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "内置角色不允许修改标识");
        }
        Long dup = roleMapper.selectCount(Wrappers.<AdminRole>lambdaQuery()
                .eq(AdminRole::getCode, dto.getCode())
                .ne(AdminRole::getId, id));
        if (dup != null && dup > 0) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "角色标识已存在");
        }
        AdminRole upd = new AdminRole();
        upd.setId(id);
        upd.setCode(dto.getCode());
        upd.setName(dto.getName());
        upd.setDescription(dto.getDescription());
        upd.setStatus(dto.getStatus());
        roleMapper.updateById(upd);
        adminPermissionService.evictAll();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRole(Long id) {
        AdminRole role = requireRole(id);
        if (Objects.equals(role.getBuiltIn(), 1)) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "内置角色不允许删除");
        }
        // 还有人在用就不给删，否则那些人会突然失去全部权限
        if (rbacMapper.countUsersOfRole(id) > 0) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "还有管理员在使用该角色");
        }
        roleMapper.deleteById(id);
        rbacMapper.clearRolePermissions(id);
        adminPermissionService.evictAll();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setRolePermissions(Long roleId, List<Long> permissionIds) {
        AdminRole role = requireRole(roleId);
        if (AdminPermissionServiceImpl.SUPER_ADMIN.equals(role.getCode())) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "超级管理员固定拥有全部权限，无需分配");
        }
        rbacMapper.clearRolePermissions(roleId);
        if (permissionIds != null && !permissionIds.isEmpty()) {
            rbacMapper.insertRolePermissions(roleId, permissionIds.stream().distinct().toList());
        }
        adminPermissionService.evictAll();
    }

    @Override
    public List<PermissionVO> permissions() {
        return permissionMapper.selectList(Wrappers.<AdminPermission>lambdaQuery()
                        .orderByAsc(AdminPermission::getSort))
                .stream().map(p -> {
                    PermissionVO vo = new PermissionVO();
                    vo.setId(p.getId());
                    vo.setCode(p.getCode());
                    vo.setName(p.getName());
                    vo.setPermGroup(p.getPermGroup());
                    vo.setSort(p.getSort());
                    return vo;
                }).toList();
    }

    @Override
    public List<AdminUserVO> admins() {
        List<AdminUserVO> list = rbacMapper.adminUsers();
        // 管理员通常只有个位数，逐个补角色名比一次 JOIN 更省事也更清晰
        for (AdminUserVO vo : list) {
            List<Long> roleIds = rbacMapper.roleIdsOf(vo.getId());
            vo.setRoleIds(roleIds);
            if (roleIds.isEmpty()) {
                vo.setRoleNames("");
            } else {
                String names = roleMapper.selectBatchIds(roleIds).stream()
                        .map(AdminRole::getName).collect(Collectors.joining("、"));
                vo.setRoleNames(names);
            }
        }
        return list;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setUserRoles(Long userId, List<Long> roleIds) {
        if (Objects.equals(userId, UserContext.getUserId())) {
            // 允许改自己会把最后一个超管锁死，直接禁止
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "不能修改自己的角色");
        }
        User user = userMapper.selectById(userId);
        if (user == null || !Objects.equals(user.getRole(), 1)) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "不是后台管理员账号");
        }
        rbacMapper.clearUserRoles(userId);
        if (roleIds != null && !roleIds.isEmpty()) {
            rbacMapper.insertUserRoles(userId, roleIds.stream().distinct().toList());
        }
        adminPermissionService.evictAll();
    }

    private AdminRole requireRole(Long id) {
        AdminRole role = roleMapper.selectById(id);
        if (role == null) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "角色不存在");
        }
        return role;
    }
}
