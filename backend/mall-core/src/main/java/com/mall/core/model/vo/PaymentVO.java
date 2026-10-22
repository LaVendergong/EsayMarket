package com.mall.core.model.vo;

import com.mall.core.entity.Payment;
import com.mall.core.enums.PayChannel;
import com.mall.core.enums.PaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Schema(description = "支付单")
public class PaymentVO {

    private Long id;

    private String paymentNo;

    private Long orderId;

    private String orderNo;

    private String channel;

    private String channelText;

    private BigDecimal amount;

    private Integer status;

    private String statusText;

    /** 前端调起参数：支付宝 {payUrl} / 微信 {codeUrl} / 模拟 {mockUrl} */
    private Map<String, Object> payParams;

    private String channelTradeNo;

    private LocalDateTime expireTime;

    private LocalDateTime paidTime;

    public static PaymentVO from(Payment p, Map<String, Object> payParams) {
        PaymentVO vo = new PaymentVO();
        vo.setId(p.getId());
        vo.setPaymentNo(p.getPaymentNo());
        vo.setOrderId(p.getOrderId());
        vo.setOrderNo(p.getOrderNo());
        vo.setChannel(p.getChannel());
        vo.setChannelText(PayChannel.textOf(p.getChannel()));
        vo.setAmount(p.getAmount());
        vo.setStatus(p.getStatus());
        vo.setStatusText(PaymentStatus.textOf(p.getStatus()));
        vo.setPayParams(payParams);
        vo.setChannelTradeNo(p.getChannelTradeNo());
        vo.setExpireTime(p.getExpireTime());
        vo.setPaidTime(p.getPaidTime());
        return vo;
    }
}
