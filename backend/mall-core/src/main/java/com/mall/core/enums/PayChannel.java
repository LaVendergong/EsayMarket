package com.mall.core.enums;

import lombok.Getter;

/**
 * 支付渠道。
 *
 * <p>三个渠道的定位很不一样，别混：
 * <ul>
 *   <li>{@link #MOCK} 本地/测试用。不需要任何商户资质，点一下就算付款成功 ——
 *       但也**绝不能**在 prod 打开（会把订单白送）</li>
 *   <li>{@link #ALIPAY_WAP} 支付宝手机网站支付（跳转收银台），需要 appId + 应用私钥 + 支付宝公钥</li>
 *   <li>{@link #WECHAT_NATIVE} 微信 Native 扫码支付，需要 mchId + 商户私钥 + APIv3 密钥 + 平台证书</li>
 * </ul>
 *
 * <p>没有商户资质时，后两个渠道的**请求构造与签名是真实可跑的**（有单测用自签密钥验证签名），
 * 只是没法真的收到钱。这一点在 README 里写清楚了。
 */
@Getter
public enum PayChannel {

    MOCK("MOCK", "模拟支付"),
    ALIPAY_WAP("ALIPAY_WAP", "支付宝"),
    WECHAT_NATIVE("WECHAT_NATIVE", "微信支付");

    private final String code;
    private final String text;

    PayChannel(String code, String text) {
        this.code = code;
        this.text = text;
    }

    public static PayChannel of(String code) {
        for (PayChannel c : values()) {
            if (c.code.equalsIgnoreCase(code)) {
                return c;
            }
        }
        return null;
    }

    public static String textOf(String code) {
        PayChannel c = of(code);
        return c == null ? "未知" : c.text;
    }
}
