package com.mall.core.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "后台角色")
public class RoleVO {

    private Long id;

    private String code;

    private String name;

    private String description;

    /** 1 = 内置，不可删除/不可改权限 */
    private Integer builtIn;

    private Integer status;

    /** 已分配的权限 id（超管为空，前端显示"全部权限"） */
    private List<Long> permissionIds;
}
