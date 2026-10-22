package com.mall.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mall.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 售后单。
 *
 * <p>注意这里**没有**加密字段：售后原因是固定选项、补充说明是用户自己写的非身份信息；
 * 真正的 PII（收货人/电话/地址）在 {@code t_order} 上，那里已经做了字段级加密。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_after_sale")
public class AfterSale extends BaseEntity {

    private String afterSaleNo;

    private Long orderId;

    private String orderNo;

    private Long userId;

    /** 1-仅退款 2-退货退款，见 AfterSaleType */
    private Integer type;

    /** 0 待处理 1 已退款 2 已拒绝 3 已撤销，见 AfterSaleStatus */
    private Integer status;

    private String reason;

    private String description;

    private BigDecimal refundAmount;

    /** 申请前的订单状态：拒绝/撤销时按它恢复，否则订单会卡在「售后中」 */
    private Integer orderStatusBefore;

    private LocalDateTime applyTime;

    private LocalDateTime handleTime;

    private String handleRemark;

    /** 商户退款单号：由售后单号派生，渠道按它做幂等（重试不会重复退钱） */
    private String refundNo;

    /** 原支付渠道：MOCK / ALIPAY_WAP / WECHAT_NATIVE */
    private String refundChannel;

    /** 0 未退款 1 退款中 2 退款成功 3 退款失败，见 RefundStatus */
    private Integer refundStatus;

    private LocalDateTime refundTime;

    /** 渠道返回的失败原因，给客服看 */
    private String refundError;
}
