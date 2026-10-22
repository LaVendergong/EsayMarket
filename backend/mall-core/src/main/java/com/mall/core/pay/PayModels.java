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
