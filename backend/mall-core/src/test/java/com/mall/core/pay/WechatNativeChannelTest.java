package com.mall.core.pay;

import com.mall.common.exception.BusinessException;
import com.mall.common.result.ResultCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 微信支付 v3 的两块密码学逻辑：请求签名（授权头）与回调报文解密。
 *
 * <p>这两块是"接微信"里最容易写错、也最不该靠肉眼检查的部分，所以用自签密钥对钉死：
 * 签名能验过、待签名串的换行位置正确、GCM 解密能还原明文。
 */
class WechatNativeChannelTest {

    private static final String BODY = "{\"appid\":\"wx1234567890\",\"mchid\":\"1900000001\",\"out_trade_no\":\"P1\",\"amount\":{\"total\":19900}}";

    private KeyPair keyPair;
    private PayProperties.Wechat cfg;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();

        cfg = new PayProperties.Wechat();
        cfg.setAppId("wx1234567890");
        cfg.setMchId("1900000001");
        cfg.setSerialNo("SERIAL123");
        cfg.setPrivateKey(Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()));
        cfg.setApiV3Key("12345678901234567890123456789012"); // 32 字节
        cfg.setNotifyUrl("https://mall.example.com/api/v1/portal/pay/notify/WECHAT_NATIVE");
    }

    @Test
    void authorizationHeader_signatureVerifiesWithTheMerchantPublicKey() {
        String timestamp = "1700000000";
        String nonce = "abc123";
        String authorization = WechatNativeChannel.buildAuthorization(
                cfg, "POST", "/v3/pay/transactions/native", BODY, timestamp, nonce);

        assertThat(authorization).startsWith("WECHATPAY2-SHA256-RSA2048 ");
        assertThat(authorization).contains("mchid=\"1900000001\"");
        assertThat(authorization).contains("nonce_str=\"abc123\"");
        assertThat(authorization).contains("serial_no=\"SERIAL123\"");

        String signature = extract(authorization, "signature");
        // 微信规范：方法\nURL\ntimestamp\nnonce\n报文\n —— 最后那个换行少一个都验不过
        String message = "POST\n/v3/pay/transactions/native\n" + timestamp + "\n" + nonce + "\n" + BODY + "\n";
        String publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        assertThat(RsaSigner.verify(message, signature, publicKey)).isTrue();

        // 报文被改一个字节就验不过
        assertThat(RsaSigner.verify(message.replace("19900", "1"), signature, publicKey)).isFalse();
    }

    @Test
    void notifyPayload_decryptsWithApiV3Key() throws Exception {
        String nonce = "abcdefghijkl";     // 12 字节
        String aad = "transaction";
        String plain = "{\"out_trade_no\":\"P1\",\"transaction_id\":\"4200001\",\"trade_state\":\"SUCCESS\",\"amount\":{\"total\":19900}}";

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKeySpec key = new SecretKeySpec(cfg.getApiV3Key().getBytes(StandardCharsets.UTF_8), "AES");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
        cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        String ciphertext = Base64.getEncoder().encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));

        String decrypted = WechatNativeChannel.decryptGcm(cfg.getApiV3Key(), aad, nonce, ciphertext);

        assertThat(decrypted).isEqualTo(plain);
    }

    @Test
    void notifyWithoutPlatformCert_isRejectedByDefault() {
        PayProperties properties = new PayProperties();
        properties.setWechat(cfg);
        WechatNativeChannel channel = new WechatNativeChannel(properties);

        // 没有平台证书 => 无法验签 => 必须拒绝（否则谁都能伪造"支付成功"）
        assertThatThrownBy(() -> channel.parseNotify(Map.of(), "{}", Map.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("平台证书未配置");
    }

    @Test
    void notConfigured_channelIsUnavailable() {
        PayProperties properties = new PayProperties();
        WechatNativeChannel channel = new WechatNativeChannel(properties);

        assertThat(channel.available()).isFalse();
        BusinessException e = org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> channel.create(PayModels.CreateCommand.builder()
                        .paymentNo("P1").orderNo("1").amount(java.math.BigDecimal.ONE).subject("x").build()));
        assertThat(e.getCode()).isEqualTo(ResultCode.PAY_CHANNEL_UNAVAILABLE.getCode());
    }

    private static String extract(String authorization, String key) {
        String marker = key + "=\"";
        int i = authorization.indexOf(marker);
        int start = i + marker.length();
        return authorization.substring(start, authorization.indexOf('"', start));
    }
}
