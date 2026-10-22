package com.mall.core.pay;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 支付配置。
 *
 * <p>三个渠道只有 {@code MOCK} 开箱即用；另外两个**必须有商户资质**（应用私钥/证书），
 * 没配时创建支付会直接报「渠道未配置」，而不是假装成功 —— 装成功比报错危险得多。
 *
 * <p>密钥都用单行 base64/PEM（去掉换行），通过环境变量注入，不要写进仓库。
 */
@Data
@Component
@ConfigurationProperties(prefix = "mall.pay")
public class PayProperties {

    /** 是否启用模拟支付渠道。dev/测试 true；**prod 必须 false**（否则等于白送） */
    private boolean mockEnabled = true;

    /** 支付单有效期（分钟），与订单超时保持一致 */
    private long expireMinutes = 30;

    private Alipay alipay = new Alipay();

    private Wechat wechat = new Wechat();

    @Data
    public static class Alipay {
        private String appId;
        /** 应用私钥（PKCS8，base64 单行） */
        private String privateKey;
        /** 支付宝公钥（base64 单行），回调验签用 */
        private String publicKey;
        private String gateway = "https://openapi.alipay.com/gateway.do";
        /** 异步通知地址，必须是公网可达的 HTTPS */
        private String notifyUrl;
        /** 同步跳转地址（用户付完款跳回哪） */
        private String returnUrl;

        public boolean configured() {
            return notBlank(appId) && notBlank(privateKey) && notBlank(publicKey);
        }
    }

    @Data
    public static class Wechat {
        private String appId;
        private String mchId;
        /** 商户 API 证书序列号 */
        private String serialNo;
        /** 商户私钥（PKCS8，base64 单行） */
        private String privateKey;
        /** APIv3 密钥（32 字节），回调报文解密用 */
        private String apiV3Key;
        /** 微信平台证书（PEM 单行），回调验签用；不配时只能解密不能验签 */
        private String platformCert;
        private String apiBase = "https://api.mch.weixin.qq.com";
        private String notifyUrl;
        /**
         * 本地联调时允许「不验签的回调」。
         * 默认 false —— 生产环境关掉验签等于谁都能伪造"支付成功"。
         */
        private boolean allowUnverifiedNotify = false;

        public boolean configured() {
            return notBlank(appId) && notBlank(mchId) && notBlank(serialNo) && notBlank(privateKey);
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
