package com.mall.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mall.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_cart")
public class Cart extends BaseEntity {

    private Long userId;

    private Long productId;

    private Long skuId;

    private Integer quantity;

    /** 0-未选中 1-选中（下单只结算选中项） */
    private Integer checked;
}
