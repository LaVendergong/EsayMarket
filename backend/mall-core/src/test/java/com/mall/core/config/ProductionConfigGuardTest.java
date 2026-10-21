package com.mall.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 生产启动自检是「不允许带着弱密钥上线」的唯一保障，必须逐条守住。
 *
 * <p>为何不用 {@code MockEnvironment}：它会把 {@code ${MALL_JWT_SECRET}} 当占位符去解析，
 * 解析不到就抛 {@code IllegalArgumentException}——而**真实环境下这个占位符是被原样保留的**
 * （实测：应用照常启动、数据库口令变成字面量，直到连接失败才以 Access denied 暴露）。
 * 所以这里用 Mockito 直接喂给 guard 它真正会看到的值，测 guard 自己的判断。
 */
class ProductionConfigGuardTest {

    private static final String GOOD_SECRET = "unit-test-secret-0123456789abcdef0123456789abcdef";
    private static final String GOOD_PASSWORD = "a-real-db-password";
    /** PII 加密密钥（必须与 JWT 密钥不同） */
    private static final String GOOD_CRYPTO = "unit-test-crypto-0123456789abcdef0123456789abcdef";

    private Environment env(boolean prod, String secret, String password) {
        return env(prod, secret, password, null, GOOD_CRYPTO);
    }

    private Environment env(boolean prod, String secret, String password, String corsOrigins) {
        return env(prod, secret, password, corsOrigins, GOOD_CRYPTO);
    }

    private Environment env(boolean prod, String secret, String password, String corsOrigins, String crypto) {
        Map<String, String> props = new HashMap<>();
        props.put("mall.jwt.secret", secret);
        props.put("spring.datasource.password", password);
        props.put("mall.crypto.secret", crypto);
        if (corsOrigins != null) {
            props.put("mall.cors.allowed-origins", corsOrigins);
        }
        Environment env = mock(Environment.class);
        when(env.acceptsProfiles(any(Profiles.class))).thenReturn(prod);
        when(env.getProperty(anyString())).thenAnswer(inv -> props.get(inv.getArgument(0, String.class)));
        return env;
    }

    // ---------------------------------------------------------------- PII 加密密钥

    @Test
    void prod_withMissingCryptoSecret_isRejected() {
        // 空串代表「环境变量没注入」
        Environment env = env(true, GOOD_SECRET, GOOD_PASSWORD, null, "");

        assertThatThrownBy(() -> new ProductionConfigGuard(env).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mall.crypto.secret");
    }

    @Test
    void prod_withDevDefaultCryptoSecret_isRejected() {
        Environment env = env(true, GOOD_SECRET, GOOD_PASSWORD, null, ProductionConfigGuard.DEV_DEFAULT_CRYPTO_SECRET);

        assertThatThrownBy(() -> new ProductionConfigGuard(env).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mall.crypto.secret");
    }

    @Test
    void prod_withShortCryptoSecret_isRejected() {
        Environment env = env(true, GOOD_SECRET, GOOD_PASSWORD, null, "x".repeat(16));

        assertThatThrownBy(() -> new ProductionConfigGuard(env).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mall.crypto.secret");
    }

    @Test
    void prod_withCryptoSecretSameAsJwtSecret_isRejected() {
        // 密钥分离：同一把密钥既签名又加密，一旦泄露两个能力一起丢
        Environment env = env(true, GOOD_SECRET, GOOD_PASSWORD, null, GOOD_SECRET);

        assertThatThrownBy(() -> new ProductionConfigGuard(env).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不能与 mall.jwt.secret 相同");
    }

    @Test
    void prod_withWildcardCorsOrigin_isRejected() {
        // 开着 allowCredentials 再配通配来源 = 任意站点可带用户凭证调后端
        Environment env = env(true, GOOD_SECRET, GOOD_PASSWORD, "https://mall.example.com,*");

        assertThatThrownBy(() -> new ProductionConfigGuard(env).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mall.cors.allowed-origins");
    }

    @Test
    void prod_withExplicitCorsOrigins_passes() {
        Environment env = env(true, GOOD_SECRET, GOOD_PASSWORD, "https://mall.example.com,https://admin.example.com");

        assertThatCode(() -> new ProductionConfigGuard(env).check()).doesNotThrowAnyException();
    }

    @Test
    void dev_profile_keeps_working_with_dev_defaults() {
        Environment env = env(false, ProductionConfigGuard.DEV_DEFAULT_JWT_SECRET, "521131");

        assertThatCode(() -> new ProductionConfigGuard(env).check()).doesNotThrowAnyException();
    }

    @Test
    void prod_with_real_values_passes() {
        Environment env = env(true, GOOD_SECRET, GOOD_PASSWORD);

        assertThatCode(() -> new ProductionConfigGuard(env).check()).doesNotThrowAnyException();
    }

    @Test
    void prod_with_dev_default_secret_is_rejected() {
        Environment env = env(true, ProductionConfigGuard.DEV_DEFAULT_JWT_SECRET, GOOD_PASSWORD);

        assertThatThrownBy(() -> new ProductionConfigGuard(env).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mall.jwt.secret");
    }

    @Test
    void prod_with_unresolved_placeholder_secret_is_rejected() {
        // 真实环境里未注入时 mall.jwt.secret 就是这串字面量
        Environment env = env(true, "${MALL_JWT_SECRET}", GOOD_PASSWORD);

        assertThatThrownBy(() -> new ProductionConfigGuard(env).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mall.jwt.secret");
    }

    @Test
    void prod_with_blank_secret_is_rejected() {
        Environment env = env(true, "   ", GOOD_PASSWORD);

        assertThatThrownBy(() -> new ProductionConfigGuard(env).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mall.jwt.secret");
    }

    @Test
    void prod_with_missing_secret_is_rejected() {
        Environment env = env(true, null, GOOD_PASSWORD);

        assertThatThrownBy(() -> new ProductionConfigGuard(env).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mall.jwt.secret");
    }

    @Test
    void prod_with_short_secret_is_rejected() {
        // 用 repeat 明确长度，别再手数字符数（上次就数错了）
        Environment env = env(true, "x".repeat(ProductionConfigGuard.MIN_SECRET_LENGTH - 1), GOOD_PASSWORD);

        assertThatThrownBy(() -> new ProductionConfigGuard(env).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("长度");
    }

    @Test
    void prod_with_secret_at_min_length_passes() {
        Environment env = env(true, "x".repeat(ProductionConfigGuard.MIN_SECRET_LENGTH), GOOD_PASSWORD);

        assertThatCode(() -> new ProductionConfigGuard(env).check()).doesNotThrowAnyException();
    }

    @Test
    void prod_with_unresolved_placeholder_db_password_is_rejected() {
        Environment env = env(true, GOOD_SECRET, "${MALL_DB_PASSWORD}");

        assertThatThrownBy(() -> new ProductionConfigGuard(env).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.datasource.password");
    }

    @Test
    void prod_with_missing_db_password_is_rejected() {
        Environment env = env(true, GOOD_SECRET, null);

        assertThatThrownBy(() -> new ProductionConfigGuard(env).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.datasource.password");
    }
}
