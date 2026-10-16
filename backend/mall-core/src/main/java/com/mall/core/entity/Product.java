package com.mall.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mall.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_product")
public class Product extends BaseEntity {

    private Long categoryId;

    private String name;

    private String subTitle;

    private String mainImage;

    /** 图集，多个 URL 用英文逗号分隔 */
    private String images;

    private String detail;

    /** 展示价；多规格商品由后台填写（一般等于最低 SKU 价） */
    private BigDecimal price;

    /** 0-下架 1-上架 */
    private Integer status;

    private Integer sales;
}
