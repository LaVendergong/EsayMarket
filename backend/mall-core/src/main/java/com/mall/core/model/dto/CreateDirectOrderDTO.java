package com.mall.core.model.dto;

import lombok.Data;

/**
 * 「立即购买」直连下单的入参：指定 SKU 与数量直接下单，不经过购物车。
 */
@Data
public class CreateDirectOrderDTO {

    /** 要购买的 SKU（必填） */
    private Long skuId;

    /** 数量，不传按 1 */
    private Integer quantity;

    /** 收货地址 ID；不传则用该用户的默认地址 */
    private Long addressId;

    /** 下单幂等键，含义与用法同 {@link CreateOrderDTO#getRequestId()} */
    private String requestId;
}
