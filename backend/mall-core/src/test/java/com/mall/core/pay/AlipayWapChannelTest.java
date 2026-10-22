package com.mall.core.pay;

import com.mall.common.exception.BusinessException;
import com.mall.common.result.ResultCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 支付宝渠道的签名/验签。
 *
 * <p>没有商户资质也能验证的部分，就把它们钉死：**用自签的 RSA 密钥对**模拟"应用私钥/支付宝公钥"，
 * 断言生成的跳转 URL 里确实带了合法签名、篡改后验不过、回调验签失败会抛错。
 * 这样"接入了支付宝"不是一句空话 —— 签名算法和待签名串拼法都和线上一致。
 */
class AlipayWapChannelTest {

    private KeyPair keyPair;
    private PayProperties properties;
    private AlipayWapChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();

        PayProperties.Alipay alipay = new PayProperties.Alipay();
        alipay.setAppId("2021000000000000");
        alipay.setPrivateKey(Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()));
        alipay.setPublicKey(Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
        alipay.setGateway("https://openapi.alipay.com/gateway.do");
        alipay.setNotifyUrl("https://mall.example.com/api/v1/portal/pay/notify/ALIPAY_WAP");

        properties = new PayProperties();
        properties.setAlipay(alipay);
        channel = new AlipayWapChannel(properties);
    }

    private PayModels.CreateCommand command() {
        return PayModels.CreateCommand.builder()
                .paymentNo("P20261021120000001")
                .orderNo("20261021120000001")
                .amount(new BigDecimal("199.00"))
                .subject("Mall 商城订单 20261021120000001")
                .build();
    }

    @Test
    void create_buildsAUrlWhoseSignatureVerifiesWithTheAlipayPublicKey() {
        PayModels.CreateResult result = channel.create(command());
        String payUrl = String.valueOf(result.getPayParams().get("payUrl"));

        assertThat(payUrl).startsWith("https://openapi.alipay.com/gateway.do?");
        assertThat(payUrl).contains("method=alipay.trade.wap.pay");

        Map<String, String> params = parseQuery(payUrl);
        String sign = params.remove("sign");
        assertThat(sign).isNotBlank();

        // 用「支付宝公钥」（这里就是自签公钥）验签：能过，说明签名与待签名串拼法正确
        String content = AlipayWapChannel.signContent(params);
        assertThat(RsaSigner.verify(content, sign, properties.getAlipay().getPublicKey())).isTrue();

        // 篡改金额后必须验不过（防中间人改价）
        Map<String, String> tampered = new LinkedHashMap<>(params);
        tampered.put("biz_content", tampered.get("biz_content").replace("199.00", "0.01"));
        assertThat(RsaSigner.verify(AlipayWapChannel.signContent(tampered), sign,
                properties.getAlipay().getPublicKey())).isFalse();
    }

    @Test
    void create_includesNotifyUrlAndTimeout() {
        String payUrl = String.valueOf(channel.create(command()).getPayParams().get("payUrl"));
        Map<String, String> params = parseQuery(payUrl);

        assertThat(params.get("notify_url")).isEqualTo("https://mall.example.com/api/v1/portal/pay/notify/ALIPAY_WAP");
        assertThat(params.get("biz_content")).contains("\"out_trade_no\":\"P20261021120000001\"");
        assertThat(params.get("biz_content")).contains("\"total_amount\":\"199.00\"");
        assertThat(params.get("sign_type")).isEqualTo("RSA2");
    }

    @Test
    void parseNotify_acceptsAProperlySignedCallback() {
        Map<String, String> params = new HashMap<>();
        params.put("out_trade_no", "P20261021120000001");
        params.put("trade_no", "2026102122001400000000000001");
        params.put("trade_status", "TRADE_SUCCESS");
        params.put("total_amount", "199.00");
        params.put("app_id", "2021000000000000");
        params.put("sign_type", "RSA2");

        // ⚠️ 支付宝这里有个不对称，很容易踩：
        //   请求签名：待签名串**包含** sign_type（只剔除 sign）
        //   通知验签：待验签串**同时剔除** sign 和 sign_type（官方 SDK 的 rsaCheckV1 就是这么做的）
        // 所以这条测试要先去掉 sign_type 再算签名，否则验签必失败。
        Map<String, String> forSign = new HashMap<>(params);
        forSign.remove("sign_type");
        String sign = RsaSigner.sign(AlipayWapChannel.signContent(forSign),
                properties.getAlipay().getPrivateKey());
        params.put("sign", sign);

        PayModels.NotifyResult r = channel.parseNotify(params, "", Map.of());

        assertThat(r.getPaymentNo()).isEqualTo("P20261021120000001");
        assertThat(r.getChannelTradeNo()).isEqualTo("2026102122001400000000000001");
        assertThat(r.isPaid()).isTrue();
        assertThat(r.getAmount()).isEqualByComparingTo("199.00");
    }

    @Test
    void parseNotify_rejectsAForgedCallback() {
        Map<String, String> params = new HashMap<>();
        params.put("out_trade_no", "P20261021120000001");
        params.put("trade_status", "TRADE_SUCCESS");
        params.put("total_amount", "0.01");        // 想改成 1 分钱
        params.put("sign", "ZmFrZS1zaWduYXR1cmU="); // 假签名

        assertThatThrownBy(() -> channel.parseNotify(params, "", Map.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("验签失败");
    }

    @Test
    void parseNotify_rejectsCallbackWithoutSignature() {
        Map<String, String> params = Map.of("out_trade_no", "P1", "trade_status", "TRADE_SUCCESS");

        BusinessException e = org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> channel.parseNotify(params, "", Map.of()));
        assertThat(e.getCode()).isEqualTo(ResultCode.PAY_NOTIFY_INVALID.getCode());
    }

    @Test
    void notConfigured_channelIsUnavailableAndFailsLoudly() {
        PayProperties empty = new PayProperties();
        AlipayWapChannel c = new AlipayWapChannel(empty);

        assertThat(c.available()).isFalse();
        BusinessException e = org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> c.create(command()));
        assertThat(e.getCode()).isEqualTo(ResultCode.PAY_CHANNEL_UNAVAILABLE.getCode());
    }

    @Test
    void refundParams_areSignedAndCarryOurRefundNo() {
        PayModels.RefundCommand cmd = PayModels.RefundCommand.builder()
                .paymentNo("P20261021120000001")
                .refundNo("RFAS20261021120000001")
                .amount(new BigDecimal("199.00"))
                .totalAmount(new BigDecimal("199.00"))
                .reason("售后退款")
                .build();

        Map<String, String> params = channel.buildRefundParams(cmd);

        assertThat(params.get("method")).isEqualTo("alipay.trade.refund");
        // out_request_no 必须是我们的退款单号 —— 支付宝按它做幂等，重试才不会重复退钱
        assertThat(params.get("biz_content")).contains("\"out_request_no\":\"RFAS20261021120000001\"");
        assertThat(params.get("biz_content")).contains("\"refund_amount\":\"199.00\"");
        assertThat(RsaSigner.verify(AlipayWapChannel.signContent(params), params.get("sign"),
                properties.getAlipay().getPublicKey())).isTrue();

        Map<String, String> tampered = new LinkedHashMap<>(params);
        tampered.put("biz_content", tampered.get("biz_content").replace("199.00", "9.99"));
        assertThat(RsaSigner.verify(AlipayWapChannel.signContent(tampered), params.get("sign"),
                properties.getAlipay().getPublicKey())).isFalse();
    }

    /** 把 URL 的 query 解析回 map（值已 URL 解码），用来断言签名 */
    private static Map<String, String> parseQuery(String url) {
        Map<String, String> out = new LinkedHashMap<>();
        String query = url.substring(url.indexOf('?') + 1);
        for (String pair : query.split("&")) {
            int i = pair.indexOf('=');
            out.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
        }
        return out;
    }
}
