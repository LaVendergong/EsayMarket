package com.mall.core.model.vo;

import com.mall.core.entity.Sku;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class SkuVO {

    private Long id;
    private String skuCode;
    private String spec;
    private String image;
    private BigDecimal price;
    /** 总库存（含被待支付订单锁定的部分） */
    private Integer stock;
    /** 已被待支付订单锁定的库存 */
    private Integer lockedStock;
    /** 可售库存 = stock - locked_stock */
    private Integer availableStock;

    /** 可售库存的唯一计算口径，列表聚合与详情都走这里 */
    public static int availableOf(Sku s) {
        int stock = s.getStock() == null ? 0 : s.getStock();
        int locked = s.getLockedStock() == null ? 0 : s.getLockedStock();
        return Math.max(stock - locked, 0);
    }

    public static SkuVO from(Sku s) {
        SkuVO vo = new SkuVO();
        vo.setId(s.getId());
        vo.setSkuCode(s.getSkuCode());
        vo.setSpec(s.getSpec());
        vo.setImage(s.getImage());
        vo.setPrice(s.getPrice());
        vo.setStock(s.getStock());
        vo.setLockedStock(s.getLockedStock());
        vo.setAvailableStock(availableOf(s));
        return vo;
    }
}
