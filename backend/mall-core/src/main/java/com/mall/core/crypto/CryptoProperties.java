package com.mall.core.crypto;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 敏感字段加密配置。
 *
 * <p>{@code mall.crypto.secret} 是字段级加密（AES-256-GCM）的**当前**主密钥，**与 JWT 签名密钥分开**
 * （密钥分离：签名密钥泄露只影响 token，不该连带把库里所有 PII 一起交出去）。
 * 生产必须通过环境变量 {@code MALL_CRYPTO_SECRET} 注入，prod 下 {@code ProductionConfigGuard} 会拦掉开发默认值。
 *
 * <h3>密钥轮换怎么做（三步，不用停机写代码）</h3>
 * <ol>
 *   <li>把新密钥放进 {@code mall.crypto.secret}，把**旧密钥**放进
 *       {@code mall.crypto.previous-secrets}（逗号分隔），重启：新写入用新密钥，
 *       旧数据靠历史密钥仍能解密（密文里带密钥 ID，见 {@link AesGcmCipher}）</li>
 *   <li>把 {@code mall.crypto.re-encrypt-on-startup} 设为 true 重启一次：
 *       启动时把仍在旧密钥下的数据重新加密成新密钥（幂等、双实例并发安全）</li>
 *   <li>确认库里没有旧 kid 之后，把 {@code previous-secrets} 和 re-encrypt 开关都清掉</li>
 * </ol>
 */
@Data
@Component
@ConfigurationProperties(prefix = "mall.crypto")
public class CryptoProperties {

    /** 当前加密主密钥；任意字符串，内部用 SHA-256 派生成 32 字节 AES 密钥 */
    private String secret;

    /**
     * 仅用于**解密**的历史密钥（逗号分隔）。轮换期的过渡配置，
     * 重加密完成后应当清空 —— 留着等于多把钥匙挂在门上。
     */
    private String previousSecrets;

    /** 启动时把库里残留的明文 PII 加密（幂等；两个应用同时跑也安全） */
    private boolean migrateOnStartup = true;

    /**
     * 启动时把「仍用历史密钥加密」的数据重加密成当前密钥。默认关闭：
     * 它是一次性的运维动作，平时不该每次启动都全表扫一遍。
     */
    private boolean reEncryptOnStartup = false;
}
