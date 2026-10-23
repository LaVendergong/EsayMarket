package com.mall.core.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "热销商品")
public class TopProductVO {

    private Long productId;

    private String productName;

    /** 卖出件数 */
    private Long quantity;

    /** 实付金额合计（已扣分摊优惠） */
    private BigDecimal amount;
}
