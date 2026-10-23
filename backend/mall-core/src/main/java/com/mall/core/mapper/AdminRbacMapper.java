package com.mall.core.mapper;

import com.mall.core.model.vo.AdminUserVO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** RBAC 的关联查询（角色/权限/用户三张关系表都不建实体，直接写 SQL） */
@Mapper
public interface AdminRbacMapper {

    @Select("SELECT r.code FROM t_admin_role r "
            + "JOIN t_admin_user_role ur ON ur.role_id = r.id "
            + "WHERE ur.user_id = #{userId} AND r.deleted = 0 AND r.status = 1")
    List<String> roleCodesOf(@Param("userId") Long userId);

    @Select("SELECT DISTINCT p.code FROM t_admin_permission p "
            + "JOIN t_admin_role_permission rp ON rp.permission_id = p.id "
            + "JOIN t_admin_role r ON r.id = rp.role_id "
            + "JOIN t_admin_user_role ur ON ur.role_id = r.id "
            + "WHERE ur.user_id = #{userId} AND r.deleted = 0 AND r.status = 1 AND p.deleted = 0")
    List<String> permissionCodesOf(@Param("userId") Long userId);

    @Select("SELECT p.code FROM t_admin_permission p WHERE p.deleted = 0")
    List<String> allPermissionCodes();

    @Select("SELECT permission_id FROM t_admin_role_permission WHERE role_id = #{roleId}")
    List<Long> permissionIdsOf(@Param("roleId") Long roleId);

    @Delete("DELETE FROM t_admin_role_permission WHERE role_id = #{roleId}")
    int clearRolePermissions(@Param("roleId") Long roleId);

    @Insert("<script>INSERT INTO t_admin_role_permission (role_id, permission_id) VALUES "
            + "<foreach collection='permissionIds' item='pid' separator=','>(#{roleId}, #{pid})</foreach>"
            + "</script>")
    int insertRolePermissions(@Param("roleId") Long roleId, @Param("permissionIds") List<Long> permissionIds);

    @Select("SELECT role_id FROM t_admin_user_role WHERE user_id = #{userId}")
    List<Long> roleIdsOf(@Param("userId") Long userId);

    /** 有多少管理员在用这个角色（删角色前检查） */
    @Select("SELECT COUNT(*) FROM t_admin_user_role WHERE role_id = #{roleId}")
    long countUsersOfRole(@Param("roleId") Long roleId);

    @Delete("DELETE FROM t_admin_user_role WHERE user_id = #{userId}")
    int clearUserRoles(@Param("userId") Long userId);

    @Insert("<script>INSERT INTO t_admin_user_role (user_id, role_id) VALUES "
            + "<foreach collection='roleIds' item='rid' separator=','>(#{userId}, #{rid})</foreach>"
            + "</script>")
    int insertUserRoles(@Param("userId") Long userId, @Param("roleIds") List<Long> roleIds);

    /** 管理员列表：role=1 的用户（后台只有一种账号类型，用不到单独的 sys_user） */
    @Select("SELECT u.id, u.username, u.nickname, u.status, u.create_time "
            + "FROM t_user u WHERE u.deleted = 0 AND u.role = 1 ORDER BY u.id")
    List<AdminUserVO> adminUsers();
}
