package com.mall.core.pricing;

import java.util.List;

public interface PricingService {

    /**
     * 计算订单金额：商品金额 → 运费 → 优惠 → 应付，并把优惠分摊到每一行。
     *
     * @param lines        商品行（单价应来自库里的 SKU，不能用前端传来的）
     * @param userId       用户（校验券归属）
     * @param userCouponId 想用的券，null = 不用券
     */
    PricingResult calculate(List<PricingLine> lines, Long userId, Long userCouponId);

    /**
     * 按商品金额比例把优惠分摊到各行（最大余额法）。
     *
     * <p>单独暴露是为了能单测：分摊错一分钱，退款时就会对不上账。
     */
    static List<java.math.BigDecimal> allocate(java.math.BigDecimal totalDiscount,
                                               List<java.math.BigDecimal> lineAmounts) {
        return PricingSupport.allocate(totalDiscount, lineAmounts);
    }
}
