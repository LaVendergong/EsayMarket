package com.mall.core.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "结算试算入参：传 skuId 走立即购买，不传则用购物车已勾选项")
public class PreviewOrderDTO {

    private Long skuId;

    private Integer quantity;

    private Long userCouponId;
}