package com.mall.core.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "后台权限点")
public class PermissionVO {

    private Long id;

    private String code;

    private String name;

    /** 分组（前端按这个分块） */
    private String permGroup;

    private Integer sort;
}
