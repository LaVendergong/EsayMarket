package com.mall.core.model.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 购物车条目（含商品/SKU 的实时快照；商品下架或 SKU 被删时 valid=false，前端提示失效）
 */
@Data
public class CartVO {

    private Long id;
    private Long productId;
    private Long skuId;
    private String productName;
    private String productImage;
    private String spec;
    private BigDecimal price;
    private Integer quantity;
    private Integer checked;
    /** 可售库存 = stock - locked_stock */
    private Integer availableStock;
    private Integer productStatus;
    private BigDecimal totalPrice;
    private Boolean valid;
}
