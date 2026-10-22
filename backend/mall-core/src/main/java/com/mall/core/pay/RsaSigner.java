package com.mall.core.pay;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * RSA2（SHA256withRSA）签名/验签 —— 支付宝和微信 v3 都是这套。
 *
 * <p>为什么自己写十几行而不用 SDK：两个渠道的请求/回调格式差异不小，但签名这一层完全一样；
 * 引两个 SDK 只为了一个 <code>sign()</code>，反而增加依赖和版本冲突风险。
 *
 * <p>密钥都是单行 base64（PKCS8 私钥 / X509 公钥），由环境变量注入。
 */
public final class RsaSigner {

    private RsaSigner() {
    }

    public static PrivateKey privateKey(String base64) {
        try {
            byte[] der = Base64.getDecoder().decode(clean(base64));
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("支付私钥格式不正确（需要 PKCS8 base64）", e);
        }
    }

    public static PublicKey publicKey(String base64OrPem) {
        try {
            byte[] der = Base64.getDecoder().decode(clean(base64OrPem));
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("支付公钥/证书格式不正确（需要 X509 base64 或 PEM）", e);
        }
    }

    /** 签名，返回 base64 */
    public static String sign(String content, String privateKeyBase64) {
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(privateKey(privateKeyBase64));
            signer.update(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (Exception e) {
            throw new IllegalStateException("支付签名失败", e);
        }
    }

    /** 验签；任何异常都视为失败（不能"验签报错就当通过"） */
    public static boolean verify(String content, String signBase64, String publicKeyBase64) {
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKeyOrCertificate(publicKeyBase64));
            verifier.update(content.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(clean(signBase64)));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 兼容两种输入：X509 公钥，或 X509 证书（PEM 里带 CERTIFICATE）。
     *
     * <p>微信给的是**平台证书**（不是公钥），要先用 CertificateFactory 解出公钥；
     * 支付宝给的是公钥。这里统一处理，调用方不用关心。
     */
    public static PublicKey publicKeyOrCertificate(String pemOrBase64) {
        if (pemOrBase64 != null && pemOrBase64.contains("CERTIFICATE")) {
            try {
                byte[] der = Base64.getDecoder().decode(clean(pemOrBase64));
                return java.security.cert.CertificateFactory.getInstance("X.509")
                        .generateCertificate(new java.io.ByteArrayInputStream(der))
                        .getPublicKey();
            } catch (Exception e) {
                throw new IllegalStateException("平台证书格式不正确", e);
            }
        }
        return publicKey(pemOrBase64);
    }

    /** 去掉 PEM 头尾与所有空白，得到纯 base64 */
    static String clean(String key) {
        if (key == null) {
            return "";
        }
        return key.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
    }
}
