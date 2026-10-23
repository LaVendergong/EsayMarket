package com.mall.core.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "新增/修改角色")
public class RbacRoleDTO {

    @NotBlank(message = "角色标识不能为空")
    @Pattern(regexp = "[A-Z][A-Z0-9_]{2,63}", message = "角色标识只能是大写字母、数字、下划线，且以字母开头")
    private String code;

    @NotBlank(message = "角色名称不能为空")
    @Size(max = 64, message = "角色名称不能超过 64 字")
    private String name;

    @Size(max = 200, message = "描述不能超过 200 字")
    private String description;

    /** 1 启用 0 停用 */
    private Integer status;
}
