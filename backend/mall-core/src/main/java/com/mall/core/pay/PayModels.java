package com.mall.core.pay;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 支付通道的输入/输出模型（放在同一个文件里，省得为三个小类建三个文件）。
 */
public final class PayModels {

    private PayModels() {
    }

    /** 创建支付单时传给渠道的参数 */
    @Data
    @Builder
    public static class CreateCommand {
        private String paymentNo;
        private String orderNo;
        private BigDecimal amount;
        /** 商品标题（渠道侧展示用），不能含用户隐私 */
        private String subject;
    }

    /**
     * 渠道返回的调起参数。
     *
     * <p>不同渠道结构不一样，所以放成 Map 交给前端按渠道渲染：
     * 支付宝是 {@code {payUrl}}，微信是 {@code {codeUrl}}，模拟是 {@code {mockUrl}}。
     */
    @Data
    @Builder
    public static class CreateResult {
        private Map<String, Object> payParams;
        private String channelTradeNo;
    }

    /** 退款请求 */
    @Data
    @Builder
    public static class RefundCommand {
        /** 原支付单号（我们的） */
        private String paymentNo;
        /** 商户退款单号：必须由业务侧生成且**可重复**，渠道按它做幂等 */
        private String refundNo;
        private BigDecimal amount;
        /** 原支付金额：微信退款接口要求同时传 total，用来校验退款不超过原额 */
        private BigDecimal totalAmount;
        private String reason;
    }

    /** 退款结果 */
    @Data
    @Builder
    public static class RefundResult {
        /** 渠道是否明确受理/完成退款 */
        private boolean success;
        /** 渠道退款单号（支付宝 refund_fee 对应的 trade_no / 微信 refund_id） */
        private String channelRefundNo;
        /** 实际走的是哪个渠道（由 PaymentService 填，售后单要记下来） */
        private String channel;
        /** 异步受理（微信可能返回 PROCESSING）：已受理但还没到账 */
        private boolean pending;
        private String raw;
    }

    /** 渠道回调解析结果 */
    @Data
    @Builder
    public static class NotifyResult {
        /** 商户支付单号（我们自己的 payment_no） */
        private String paymentNo;
        private String channelTradeNo;
        /** 渠道是否确认这笔钱收到了 */
        private boolean paid;
        /** 渠道回传的实付金额，必须与支付单一致 */
        private BigDecimal amount;
        private String raw;
    }
}
