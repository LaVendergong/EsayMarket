package com.mall.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mall.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_user_coupon")
public class UserCoupon extends BaseEntity {

    private Long userId;

    private Long couponId;

    /** 0 未使用 1 已使用 2 已过期 */
    private Integer status;

    /** 锁定它的订单 */
    private Long orderId;

    private LocalDateTime receiveTime;

    private LocalDateTime useTime;
}