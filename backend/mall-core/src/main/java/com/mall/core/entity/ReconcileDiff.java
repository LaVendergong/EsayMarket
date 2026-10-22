package com.mall.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mall.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 对账差异：需要人工逐笔处理的那份清单。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_reconcile_diff")
public class ReconcileDiff extends BaseEntity {

    private Long batchId;

    /** PAY / REFUND */
    private String bizType;

    /** 商户退款单号（bizType=REFUND 时有值） */
    private String refundNo;

    /** MISSING_LOCAL / MISSING_CHANNEL / AMOUNT_MISMATCH */
    private String diffType;

    private String paymentNo;

    private String channelTradeNo;

    private BigDecimal localAmount;

    private BigDecimal channelAmount;

    private String detail;
}
