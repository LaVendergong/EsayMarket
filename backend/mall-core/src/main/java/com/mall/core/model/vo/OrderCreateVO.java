package com.mall.core.model.vo;

import com.mall.core.entity.Order;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 下单成功返回
 */
@Data
public class OrderCreateVO {

    private Long orderId;
    private String orderNo;
    private BigDecimal payAmount;
    private Integer status;
    private LocalDateTime expireTime;

    public static OrderCreateVO from(Order o) {
        OrderCreateVO vo = new OrderCreateVO();
        vo.setOrderId(o.getId());
        vo.setOrderNo(o.getOrderNo());
        vo.setPayAmount(o.getPayAmount());
        vo.setStatus(o.getStatus());
        vo.setExpireTime(o.getExpireTime());
        return vo;
    }
}
