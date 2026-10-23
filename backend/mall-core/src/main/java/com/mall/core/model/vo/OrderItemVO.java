package com.mall.core.model.vo;

import com.mall.core.entity.OrderItem;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class OrderItemVO {

    private Long id;
    private Long productId;
    private Long skuId;
    private String productName;
    private String productImage;
    private String spec;
    private BigDecimal price;
    private Integer quantity;
    private BigDecimal totalAmount;
    /** 该行分摊到的优惠 */
    private BigDecimal discountAmount;
    /** 该行实付 = totalAmount - discountAmount */
    private BigDecimal payAmount;

    public static OrderItemVO from(OrderItem i) {
        OrderItemVO vo = new OrderItemVO();
        vo.setId(i.getId());
        vo.setProductId(i.getProductId());
        vo.setSkuId(i.getSkuId());
        vo.setProductName(i.getProductName());
        vo.setProductImage(i.getProductImage());
        vo.setSpec(i.getSpec());
        vo.setPrice(i.getPrice());
        vo.setQuantity(i.getQuantity());
        vo.setTotalAmount(i.getTotalAmount());
        vo.setDiscountAmount(i.getDiscountAmount());
        vo.setPayAmount(i.getPayAmount());
        return vo;
    }
}
