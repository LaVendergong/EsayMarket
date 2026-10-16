package com.mall.core.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CategoryDTO {

    /** 父分类 ID，不传或 0 表示顶级 */
    private Long parentId;

    @NotBlank(message = "分类名称不能为空")
    @Size(max = 64, message = "长度不能超过 64")
    private String name;

    /** 排序值，不传默认 0 */
    private Integer sort;

    private String icon;

    /** 0-禁用 1-正常，不传默认 1 */
    private Integer status;
}
