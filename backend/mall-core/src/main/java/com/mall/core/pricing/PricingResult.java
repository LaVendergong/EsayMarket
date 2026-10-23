package com.mall.core.pricing;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 计价结果。
 *
 * <p>金额恒等式（务必保持）：{@code payAmount = goodsAmount + freightAmount - discountAmount}
 */
@Data
@Builder
public class PricingResult {

    /** 商品金额（不含运费、不含优惠） */
    private BigDecimal goodsAmount;

    private BigDecimal freightAmount;

    private BigDecimal discountAmount;

    /** 应付 = 商品 + 运费 - 优惠 */
    private BigDecimal payAmount;

    /** 实际生效的用户券 id（null = 没用券） */
    private Long userCouponId;

    /** 每行分摊结果，顺序与入参一致 */
    private List<LineShare> lines;

    @Data
    @Builder
    public static class LineShare {
        private Long skuId;
        private BigDecimal lineAmount;
        /** 该行分摊到的优惠 */
        private BigDecimal discountAmount;
        /** 该行实付 = lineAmount - discountAmount */
        private BigDecimal payAmount;
    }
}
