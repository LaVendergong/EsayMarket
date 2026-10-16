package com.mall.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mall.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_order")
public class Order extends BaseEntity {

    private String orderNo;

    /** 下单幂等键：由客户端在一次结算里固定不变地传入，(user_id, request_id) 上有唯一键 */
    private String requestId;

    private Long userId;

    private BigDecimal totalAmount;

    private BigDecimal payAmount;

    /** 见 com.mall.core.enums.OrderStatus */
    private Integer status;

    private String receiver;

    private String receiverPhone;

    private String receiverAddr;

    private LocalDateTime payTime;

    /** 未支付订单的关单时间点（超时关单定时任务扫描用） */
    private LocalDateTime expireTime;
}
