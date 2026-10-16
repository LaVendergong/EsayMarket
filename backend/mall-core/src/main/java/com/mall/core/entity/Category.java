package com.mall.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mall.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_category")
public class Category extends BaseEntity {

    /** 父分类 ID，0 表示顶级 */
    private Long parentId;

    private String name;

    /** 排序值，越小越靠前 */
    private Integer sort;

    private String icon;

    /** 0-禁用 1-正常 */
    private Integer status;
}
