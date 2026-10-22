package com.mall.core.pay.bill;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 账单里的一行（已归一化）。
 *
 * <p>各渠道账单的列名、顺序、表头行数都不一样，解析器负责把它们映射成这个统一结构；
 * 对账引擎只认这个结构，不关心渠道差异。
 */
@Data
@Builder
public class BillLine {

    /** 商户订单号（我们自己的 payment_no） */
    private String outTradeNo;

    /** 商户退款单号（退款行才有）：退款对账靠它和本地售后单匹配 */
    private String outRefundNo;

    /** 渠道交易号 */
    private String channelTradeNo;

    private BigDecimal amount;

    /** 渠道状态原文：SUCCESS / TRADE_SUCCESS / 退款 ... */
    private String status;

    /** PAY 或 REFUND（v1 只用 PAY） */
    private String bizType;
}
