package com.mall.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mall.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付单。每一次「去支付」都会创建一条，记录渠道、金额、调起参数与回调结果。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_payment")
public class Payment extends BaseEntity {

    private String paymentNo;

    private Long orderId;

    private String orderNo;

    private Long userId;

    /** MOCK / ALIPAY_WAP / WECHAT_NATIVE */
    private String channel;

    private BigDecimal amount;

    /** 0 待支付 1 已支付 2 已关闭 3 支付失败 */
    private Integer status;

    /** 调起参数（JSON）：支付宝是跳转 URL，微信是 code_url */
    private String payParams;

    private String channelTradeNo;

    private LocalDateTime paidTime;

    private LocalDateTime expireTime;

    private LocalDateTime notifyTime;

    private String notifyRaw;
}
