package com.mall.core.service;

import com.mall.core.model.vo.PaymentVO;

import java.util.List;
import java.util.Map;

public interface PaymentService {

    /** 可用的支付渠道（前端收银台据此渲染，未配置的不会出现） */
    List<Map<String, String>> availableChannels();

    /** 为订单创建一笔支付单；同一订单的旧待支付单会被关闭 */
    PaymentVO create(Long userId, Long orderId, String channelCode);

    /** 查询支付单状态（前端轮询用） */
    PaymentVO detail(Long userId, String paymentNo);

    /** 模拟支付成功（仅 MOCK 渠道可用） */
    void mockPay(Long userId, String paymentNo);

    /** 用户主动关闭支付单 */
    void close(Long userId, String paymentNo);

    /** 渠道异步回调：验签 -> 校验金额 -> 幂等置为已支付 -> 订单置为已支付 */
    void handleNotify(String channelCode, Map<String, String> params, String body, Map<String, String> headers);

    /** 兼容旧的「去支付」接口：等价于用 MOCK 渠道创建并立即支付 */
    PaymentVO payWithMock(Long userId, Long orderId);

    /**
     * 原路退款：找到该订单成功的那笔支付，按它当时的渠道退回去。
     *
     * @param refundNo 商户退款单号，必须**可重复**（重试复用同一个号，渠道据此幂等）
     */
    com.mall.core.pay.PayModels.RefundResult refund(Long orderId, String refundNo,
                                                   java.math.BigDecimal amount, String reason);

    /** 渠道退款结果通知：验签 -> 把「退款中」推进到「退款成功 / 退款异常」 */
    void handleRefundNotify(String channelCode, Map<String, String> params, String body, Map<String, String> headers);

    /** 主动查一次退款单（后台手动触发），返回查完之后的退款状态码 */
    int queryRefund(Long afterSaleId);

    /** 扫描「退款中」的售后单主动查询收敛（定时任务用），返回处理条数 */
    int reconcileProcessingRefunds(int limit);
}
