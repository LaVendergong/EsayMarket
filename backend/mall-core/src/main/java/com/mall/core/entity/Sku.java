package com.mall.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mall.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_sku")
public class Sku extends BaseEntity {

    private Long productId;

    private String skuCode;

    /** 规格描述，如 "256G 黑色" */
    private String spec;

    private String image;

    private BigDecimal price;

    /** 可售库存 */
    private Integer stock;

    /** 已锁定库存（下单未支付），P3 使用 */
    private Integer lockedStock;
}
