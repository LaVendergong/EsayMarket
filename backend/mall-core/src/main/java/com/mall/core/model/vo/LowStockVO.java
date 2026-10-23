package com.mall.core.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "低库存 SKU")
public class LowStockVO {

    private Long skuId;

    private Long productId;

    private String productName;

    private String spec;

    /** 物理库存 */
    private Integer stock;

    /** 已锁定（未支付订单占用） */
    private Integer lockedStock;

    /** 可售 = stock - lockedStock */
    private Integer available;
}
