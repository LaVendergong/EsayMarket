package com.mall.core.model.vo;

import com.mall.core.entity.Order;
import com.mall.core.enums.OrderStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class OrderVO {

    private Long id;
    private String orderNo;
    private Long userId;
    private BigDecimal totalAmount;
    private BigDecimal payAmount;
    private Integer status;
    private String statusText;
    private String receiver;
    private String receiverPhone;
    private String receiverAddr;
    private LocalDateTime payTime;
    private LocalDateTime expireTime;
    private LocalDateTime createTime;
    private List<OrderItemVO> items;

    public static OrderVO from(Order o, List<OrderItemVO> items) {
        OrderVO vo = new OrderVO();
        vo.setId(o.getId());
        vo.setOrderNo(o.getOrderNo());
        vo.setUserId(o.getUserId());
        vo.setTotalAmount(o.getTotalAmount());
        vo.setPayAmount(o.getPayAmount());
        vo.setStatus(o.getStatus());
        vo.setStatusText(OrderStatus.textOf(o.getStatus()));
        vo.setReceiver(o.getReceiver());
        vo.setReceiverPhone(o.getReceiverPhone());
        vo.setReceiverAddr(o.getReceiverAddr());
        vo.setPayTime(o.getPayTime());
        vo.setExpireTime(o.getExpireTime());
        vo.setCreateTime(o.getCreateTime());
        vo.setItems(items);
        return vo;
    }
}
