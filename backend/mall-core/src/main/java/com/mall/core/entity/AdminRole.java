package com.mall.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mall.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 后台角色 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_admin_role")
public class AdminRole extends BaseEntity {

    /** 角色标识，如 SUPER_ADMIN */
    private String code;

    private String name;

    private String description;

    /** 1 = 内置角色，不允许删除 */
    private Integer builtIn;

    /** 1 启用 0 停用 */
    private Integer status;
}
