package com.mall.core.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "mall.jwt")
public class JwtProperties {

    /** HMAC 密钥，长度至少 32 字节 */
    private String secret;

    /** token 有效期（毫秒），默认 7 天 */
    private long expireMillis = 604800000L;
}
