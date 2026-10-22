package com.mall.core.pay;

import com.mall.common.exception.BusinessException;
import com.mall.common.result.ResultCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模拟渠道的退款语义。
 *
 * <p>它是本地跑通「售后 → 退款 → 渠道」这条链路的执行者，所以「退款金额不能超过原支付金额」
 * 这条必须由它自己守住 —— 不能指望上层每个调用方都记着校验。
 */
class MockPaymentChannelTest {

    private MockPaymentChannel channel(boolean enabled) {
        PayProperties p = new PayProperties();
        p.setMockEnabled(enabled);
        return new MockPaymentChannel(p);
    }

    @Test
    void refund_succeedsAndReturnsAChannelRefundNo() {
        PayModels.RefundResult r = channel(true).refund(PayModels.RefundCommand.builder()
                .paymentNo("P1").refundNo("RF-1")
                .amount(new BigDecimal("50.00")).totalAmount(new BigDecimal("50.00"))
                .build());

        assertThat(r.isSuccess()).isTrue();
        assertThat(r.isPending()).isFalse();
        assertThat(r.getChannelRefundNo()).isEqualTo("MOCKREFUND-RF-1");
    }

    @Test
    void refund_refusesMoreThanTheOriginalPayment() {
        assertThatThrownBy(() -> channel(true).refund(PayModels.RefundCommand.builder()
                .paymentNo("P1").refundNo("RF-2")
                .amount(new BigDecimal("60.00")).totalAmount(new BigDecimal("50.00"))
                .build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能大于原支付金额");
    }

    @Test
    void refund_whenMockDisabled_isRejected() {
        BusinessException e = org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> channel(false).refund(PayModels.RefundCommand.builder()
                        .paymentNo("P1").refundNo("RF-3")
                        .amount(BigDecimal.ONE).totalAmount(BigDecimal.ONE).build()));
        assertThat(e.getCode()).isEqualTo(ResultCode.PAY_CHANNEL_UNAVAILABLE.getCode());
    }
}
