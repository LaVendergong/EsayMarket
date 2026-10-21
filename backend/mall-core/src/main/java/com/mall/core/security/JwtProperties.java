package com.mall.core.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 相关配置。
 *
 * <p>有效期拆成两个：
 * <ul>
 *   <li>{@code accessExpireMillis}（默认 15 分钟）：JWT 本身的有效期。**短**是为了让泄露的 token
 *       窗口尽可能小 —— 它是无状态、无法吊销的。</li>
 *   <li>{@code refreshExpireMillis}（默认 7 天）：刷新令牌的有效期。它存在库里、可吊销、用一次即换新。</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "mall.jwt")
public class JwtProperties {

    /** HMAC 密钥，长度至少 32 字节 */
    private String secret;

    /** access token（JWT）有效期（毫秒），默认 15 分钟 */
    private long accessExpireMillis = 900000L;

    /** refresh token 有效期（毫秒），默认 7 天 */
    private long refreshExpireMillis = 604800000L;
}