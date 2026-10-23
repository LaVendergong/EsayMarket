package com.mall.core.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Schema(description = "结算试算结果")
public class OrderPreviewVO {

    private BigDecimal goodsAmount;

    private BigDecimal freightAmount;

    private BigDecimal discountAmount;

    private BigDecimal payAmount;

    private Long userCouponId;

    /** 1 实物 2 虚拟：虚拟订单前台不展示地址块 */
    private Integer orderType;

    private String orderTypeText;

    private List<Item> items;

    /** 当前金额下可用的券（可用的排前面） */
    private List<UserCouponVO> coupons;

    @Data
    @Schema(description = "试算明细行")
    public static class Item {
        private Long productId;
        private Long skuId;
        private String productName;
        private String image;
        private String spec;
        private BigDecimal price;
        private Integer quantity;
        private BigDecimal lineAmount;
        private BigDecimal discountAmount;
        private BigDecimal payAmount;
    }
}