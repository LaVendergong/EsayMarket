package com.mall.core.model.vo;

import com.mall.core.entity.Order;
import com.mall.core.enums.ExpressCompany;
import com.mall.core.enums.OrderStatus;
import com.mall.core.enums.ProductType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class OrderVO {

    private Long id;
    private String orderNo;
    private Long userId;
    /** 商品金额（不含运费、不含优惠） */
    private BigDecimal totalAmount;
    private BigDecimal freightAmount;
    private BigDecimal discountAmount;
    /** 应付 = 商品 + 运费 - 优惠 */
    private BigDecimal payAmount;
    private Long userCouponId;
    private Integer status;
    private String statusText;
    /** 1 实物 2 虚拟 */
    private Integer orderType;
    private String orderTypeText;

    /** 物流：发货后才有值 */
    private String shipCompany;
    private String shipCompanyText;
    private String shipNo;
    private LocalDateTime shipTime;
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
        vo.setFreightAmount(o.getFreightAmount());
        vo.setDiscountAmount(o.getDiscountAmount());
        vo.setPayAmount(o.getPayAmount());
        vo.setUserCouponId(o.getUserCouponId());
        vo.setStatus(o.getStatus());
        vo.setStatusText(OrderStatus.textOf(o.getStatus()));
        vo.setOrderType(ProductType.of(o.getOrderType()).getCode());
        vo.setOrderTypeText(ProductType.textOf(o.getOrderType()));
        vo.setShipCompany(o.getShipCompany());
        vo.setShipCompanyText(ExpressCompany.textOf(o.getShipCompany()));
        vo.setShipNo(o.getShipNo());
        vo.setShipTime(o.getShipTime());
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
