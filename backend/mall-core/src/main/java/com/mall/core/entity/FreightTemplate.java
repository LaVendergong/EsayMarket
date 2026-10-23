package com.mall.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mall.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_freight_template")
public class FreightTemplate extends BaseEntity {

    private String name;

    /** FIXED / FREE_OVER */
    private String type;

    private BigDecimal baseFee;

    /** 满多少包邮（FREE_OVER 时有效） */
    private BigDecimal freeThreshold;

    /** 1 启用 0 停用 */
    private Integer status;
}