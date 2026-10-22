package com.mall.core.pay;

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
     * 解析并校验渠道回调。
     *
     * @param params  表单参数（支付宝回调是 form）
     * @param body    原始报文（微信 v3 回调是 JSON）
     * @param headers 请求头（微信 v3 验签需要 Wechatpay-Signature 等）
     * @return 解析结果；**验签失败必须抛异常**，不能返回 paid=false 蒙混过关
     */
    PayModels.NotifyResult parseNotify(Map<String, String> params, String body, Map<String, String> headers);
}
