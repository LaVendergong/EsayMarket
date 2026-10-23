package com.mall.core.pricing;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 参与计价的一行（下单前的原始商品行）。
 *
 * <p>只带计价需要的字段：谁（product/sku）、多少件、单价、用哪个运费模板。
 */
@Data
@AllArgsConstructor
public class PricingLine {

    private Long productId;

    private Long skuId;

    private Integer quantity;

    /** 单价（取自 SKU，不信任前端） */
    private BigDecimal price;

    /** 运费模板 id；null = 包邮 */
    private Long freightTemplateId;

    /** 行金额 = 单价 × 数量 */
    public BigDecimal lineAmount() {
        return price.multiply(BigDecimal.valueOf(quantity == null ? 0 : quantity))
                .setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
