package com.mall.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mall.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 优惠券模板（满减券） */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_coupon")
public class Coupon extends BaseEntity {

    private String name;

    /** 使用门槛（按商品金额判断，不含运费） */
    private BigDecimal threshold;

    private BigDecimal amount;

    private Integer total;

    private Integer received;

    private LocalDateTime validFrom;

    private LocalDateTime validTo;

    /** 1 可领 0 停发 */
    private Integer status;
}