package com.mall.core.pay;

import com.mall.common.exception.BusinessException;
import com.mall.common.result.ResultCode;
import com.mall.core.enums.PayChannel;

import java.util.Map;

/**
 * 支付通道 SPI。
 *
 * <p>新增一个渠道只要实现这个接口 + 起个 Bean，{@code PaymentServiceImpl} 会按
 * {@link #channel()} 自动注册 —— 加渠道不用改任何业务代码。
 */
public interface PaymentChannel {

    PayChannel channel();

    /** 配置是否齐全；不齐时创建支付会直接报错，而不是"看起来成功了" */
    boolean available();

    /** 创建支付，返回前端调起参数 */
    PayModels.CreateResult create(PayModels.CreateCommand command);

    /**
     * 该渠道是否支持原路退款。
     *
     * <p>默认支持；如果某个渠道只能收不能退（例如某些虚拟账户），返回 false，
     * 售后审批会退化成"人工线下退款"，而不是假装退成功。
     */
    default boolean supportsRefund() {
        return true;
    }

    /**
     * 原路退款。
     *
     * <p>必须做到两点：
     * <ul>
     *   <li><b>幂等</b>：同一个 {@code refundNo} 重复调用不能退两次（支付宝按 out_request_no、微信按 out_refund_no）</li>
     *   <li><b>不抛"成功"</b>：渠道没明确说成功，就返回 success=false，不能吞掉</li>
     * </ul>
     */
    PayModels.RefundResult refund(PayModels.RefundCommand command);

    /**
     * 主动查支付单状态（回调丢失时的兜底）。
     *
     * <p>回调会丢：用户明明付了钱，我们没收到通知，订单就一直挂到超时关单。
     * 有了它，定时任务能把这类单救回来。
     */
    default PayModels.PayQueryResult queryPayment(PayModels.PayQuery query) {
        throw new BusinessException(ResultCode.PAY_QUERY_NOT_SUPPORTED, "该渠道不支持支付查询");
    }

    /**
     * 主动查退款单状态（对账兜底）。
     *
     * <p>为什么要它：退款通知可能丢（网络抖动、回调地址配错、服务重启），
     * 只靠通知的话「退款中」会永远停在那里。定时任务用它把状态收敛掉。
     */
    default PayModels.RefundNotifyResult queryRefund(PayModels.RefundQuery query) {
        throw new BusinessException(ResultCode.REFUND_NOT_SUPPORTED, "该渠道不支持退款查询");
    }

    /**
     * 解析退款异步通知。
     *
     * <p>和支付通知一样，**验签失败必须抛异常**；不支持退款通知的渠道也直接抛。
     */
    default PayModels.RefundNotifyResult parseRefundNotify(Map<String, String> params, String body,
                                                          Map<String, String> headers) {
        throw new BusinessException(ResultCode.REFUND_NOT_SUPPORTED, "该渠道没有退款异步通知");
    }

    /**
     * 解析并校验渠道回调。
     *
     * @param params  表单参数（支付宝回调是 form）
     * @param body    原始报文（微信 v3 回调是 JSON）
     * @param headers 请求头（微信 v3 验签需要 Wechatpay-Signature 等）
     * @return 解析结果；**验签失败必须抛异常**，不能返回 paid=false 蒙混过关
     */
    PayModels.NotifyResult parseNotify(Map<String, String> params, String body, Map<String, String> headers);
}
