package com.mall.core.crypto;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 敏感字段加密配置。
 *
 * <p>{@code mall.crypto.secret} 是字段级加密（AES-256-GCM）的主密钥，**与 JWT 签名密钥分开**
 * （密钥分离：签名密钥泄露只影响 token，不该连带把库里所有 PII 一起交出去）。
 * 生产必须通过环境变量 {@code MALL_CRYPTO_SECRET} 注入，prod 下 {@code ProductionConfigGuard} 会拦掉开发默认值。
 */
@Data
@Component
@ConfigurationProperties(prefix = "mall.crypto")
public class CryptoProperties {

    /** 字段加密主密钥；任意字符串，内部用 SHA-256 派生成 32 字节 AES 密钥 */
    private String secret;

    /** 是否在启动时把库里残留的明文 PII 加密（幂等；两个应用同时跑也安全） */
    private boolean migrateOnStartup = true;
}
