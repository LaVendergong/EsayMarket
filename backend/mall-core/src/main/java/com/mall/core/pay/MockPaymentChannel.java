package com.mall.core.pay;

import com.mall.common.exception.BusinessException;
import com.mall.common.result.ResultCode;
import com.mall.core.enums.PayChannel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模拟支付渠道（本地/自动化测试用）。
 *
 * <p>它把「调起支付」简化成一个后端接口：前端拿到 paymentNo 后调
 * {@code POST /portal/payment/{paymentNo}/mock-pay} 就算付款成功。
 *
 * <p><b>prod 必须关掉</b>（{@code mall.pay.mock-enabled=false}）——
 * 否则任何人都能把自己的订单"付"成已支付，等于白送。prod 自检会拦。
 */
@Component
@RequiredArgsConstructor
public class MockPaymentChannel implements PaymentChannel {

    private final PayProperties properties;

    @Override
    public PayChannel channel() {
        return PayChannel.MOCK;
    }

    @Override
    public boolean available() {
        return properties.isMockEnabled();
    }

    @Override
    public PayModels.CreateResult create(PayModels.CreateCommand command) {
        if (!available()) {
            throw new BusinessException(ResultCode.PAY_CHANNEL_UNAVAILABLE, "模拟支付已关闭");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("mockUrl", "/api/v1/portal/payment/" + command.getPaymentNo() + "/mock-pay");
        params.put("paymentNo", command.getPaymentNo());
        return PayModels.CreateResult.builder().payParams(params).build();
    }

    /**
     * 模拟退款：直接成功。
     *
     * <p>仍然走完整的「售后 -> 支付模块 -> 渠道」链路，所以状态机、幂等（同一 refundNo 只记一次）
     * 这些逻辑在本地就能验到；换成真实渠道时只是最后一个实现类不同。
     */
    @Override
    public PayModels.RefundResult refund(PayModels.RefundCommand command) {
        if (!available()) {
            throw new BusinessException(ResultCode.PAY_CHANNEL_UNAVAILABLE, "模拟支付已关闭");
        }
        if (command.getAmount() != null && command.getTotalAmount() != null
                && command.getAmount().compareTo(command.getTotalAmount()) > 0) {
            throw new BusinessException(ResultCode.REFUND_FAILED, "退款金额不能大于原支付金额");
        }
        return PayModels.RefundResult.builder()
                .success(true)
                .pending(false)
                .channelRefundNo("MOCKREFUND-" + command.getRefundNo())
                .raw("{\"mock\":true}")
                .build();
    }

    @Override
    public PayModels.NotifyResult parseNotify(Map<String, String> params, String body, Map<String, String> headers) {
        // 模拟渠道没有真实回调；这条路径只被 mock-pay 接口用到
        throw new BusinessException(ResultCode.PAY_CHANNEL_UNAVAILABLE, "模拟支付没有异步回调");
    }
}
