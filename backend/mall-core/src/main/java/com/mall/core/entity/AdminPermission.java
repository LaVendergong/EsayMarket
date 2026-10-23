package com.mall.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mall.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 后台权限点 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_admin_permission")
public class AdminPermission extends BaseEntity {

    /** 权限码：模块:动作，如 order:ship */
    private String code;

    private String name;

    /** 分组（前端按这个分块展示） */
    private String permGroup;

    private Integer sort;
}
