package com.mall.core.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * 生产环境（激活 prod profile）启动自检：敏感配置没真正注入时，让应用**起不来**，
 * 而不是带着开发默认密钥/错口令悄悄跑起来。
 *
 * <p>为什么非要写这段代码（实测踩过）：Spring Boot 在 {@code @ConfigurationProperties}
 * 绑定路径上用的是 {@code PropertySourcesPlaceholdersResolver}，它构造 {@code PropertyPlaceholderHelper}
 * 时是 {@code ignoreUnresolvablePlaceholders = true}——也就是**解析不了的占位符会原样保留**，
 * 不像 {@code @Value} 那样抛异常。实测把配置写成 {@code secret: ${MALL_JWT_SECRET}} 且不设环境变量时：
 * 应用照常启动，JWT 密钥变成字面量 {@code "${MALL_JWT_SECRET}"}，数据库口令同样变成字面量，
 * 直到业务跑起来才以 “Access denied” 这种误导性错误暴露。所以这里显式校验。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductionConfigGuard {

    /** 与 application.yml 里的开发默认值保持一致；出现在 prod 里就说明没注入真实值 */
    static final String DEV_DEFAULT_JWT_SECRET = "mall-dev-only-INSECURE-secret-0123456789abcdef";
    /** 与两个 application.yml 里 mall.crypto.secret 的默认值保持一致 */
    static final String DEV_DEFAULT_CRYPTO_SECRET = "mall-dev-only-INSECURE-crypto-secret-0123456789abcdef";
    /** HS256 要求密钥至少 256 bit */
    static final int MIN_SECRET_LENGTH = 32;

    private final Environment env;

    @PostConstruct
    public void check() {
        if (!env.acceptsProfiles(Profiles.of("prod"))) {
            return;
        }

        String secret = requireReal("mall.jwt.secret", DEV_DEFAULT_JWT_SECRET);
        if (secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(String.format(
                    "[prod 启动自检失败] mall.jwt.secret 长度只有 %d，HS256 要求至少 %d 个字符",
                    secret.length(), MIN_SECRET_LENGTH));
        }
        // 数据库口令只要求「真被注入」；是否等于开发默认值是 DBA 的事，这里不做一刀切，
        // 否则本地想按 prod 方式自测都会被挡住
        requireReal("spring.datasource.password", null);
        checkCorsOrigins();
        checkCryptoSecret();
        checkCookieSecure();
        checkMockPayment();

        log.info("[prod 启动自检] 通过：JWT 密钥、数据库口令、PII 加密密钥均已从环境变量注入");
    }

    /**
     * prod 下不允许 CORS 通配来源。
     *
     * <p>本项目开着 {@code allowCredentials=true}，一旦来源是 {@code *}，
     * 任意站点都能带着用户凭证调后端 —— 这是最典型的「看起来能跑、实际已经失守」的配置。
     */
    private void checkCorsOrigins() {
        String origins = env.getProperty("mall.cors.allowed-origins");
        if (origins == null) {
            return;
        }
        for (String origin : origins.split(",")) {
            if ("*".equals(origin.trim())) {
                throw new IllegalStateException("[prod 启动自检失败] mall.cors.allowed-origins 不能包含 *"
                        + "（本项目开启 allowCredentials，通配来源等于允许任意站点携带用户凭证调用）");
            }
        }
    }

    /**
     * prod 下登录态 Cookie 必须是 Secure 的。
     *
     * <p>Cookie 的 Secure 标记决定它「只在 HTTPS 下发送」。
     * prod 若还是 false，等于把登录态放在明文连接上跑 —— 而 Cookie 是浏览器自动携带的，
     * 一旦被嗅探，攻击者拿到的是完整的登录态。
     * 反过来说，这条检查也强制了 prod 必须部署在 TLS 后面（没有 HTTPS 就别想上线）。
     */
    private void checkCookieSecure() {
        String secure = env.getProperty("mall.security.cookie.secure");
        if (!"true".equalsIgnoreCase(secure)) {
            throw new IllegalStateException("[prod 启动自检失败] mall.security.cookie.secure 必须为 true："
                    + "登录态存在 HttpOnly Cookie 里，prod 必须在 HTTPS 后面（设 MALL_COOKIE_SECURE=true）");
        }
    }

    /**
     * prod 下必须关掉「模拟支付」。
     *
     * <p>模拟支付渠道只要调一个接口就能把订单标成已支付（不需要真付钱）。
     * 这个开关留在 prod 等于「谁都能白拿商品」，属于灾难级配置错误，必须启动即失败。
     */
    private void checkMockPayment() {
        String mock = env.getProperty("mall.pay.mock-enabled");
        if ("true".equalsIgnoreCase(mock)) {
            throw new IllegalStateException("[prod 启动自检失败] mall.pay.mock-enabled 必须为 false："
                    + "模拟支付渠道不需要真实付款就能把订单标成已支付，prod 打开等于白送商品");
        }
    }

    /**
     * prod 下 PII 字段加密密钥必须真的注入过。
     *
     * <p>用开发默认密钥上线，等于库里所有手机号/收货地址都拿一个公开在仓库里的密钥加密 ——
     * 比明文好不了多少（拿到库的人同时也能拿到仓库）。
     */
    private void checkCryptoSecret() {
        String secret = requireReal("mall.crypto.secret", DEV_DEFAULT_CRYPTO_SECRET);
        if (secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(String.format(
                    "[prod 启动自检失败] mall.crypto.secret 长度只有 %d，至少需要 %d（建议 32+）",
                    secret.length(), MIN_SECRET_LENGTH));
        }
        if (secret.equals(env.getProperty("mall.jwt.secret"))) {
            // 密钥分离：签名密钥和加密密钥不能是同一把
            throw new IllegalStateException("[prod 启动自检失败] mall.crypto.secret 不能与 mall.jwt.secret 相同"
                    + "（签名密钥泄露时不该连带导致库里 PII 全部可解）");
        }
    }

    /**
     * 取值并校验它不是「假的」：不能为空、不能是解析失败后残留的占位符字面量，
     * 传了 {@code devDefault} 时还不能等于开发默认值。
     *
     * @return 校验通过的原始值
     */
    private String requireReal(String key, String devDefault) {
        String value = env.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "[prod 启动自检失败] 配置项 " + key + " 为空，请通过环境变量注入真实值");
        }
        if (value.contains("${")) {
            throw new IllegalStateException("[prod 启动自检失败] 配置项 " + key
                    + " 仍是未解析的占位符（" + value + "），说明对应的环境变量没有设置");
        }
        if (devDefault != null && value.equals(devDefault)) {
            throw new IllegalStateException("[prod 启动自检失败] 配置项 " + key
                    + " 还是开发默认值，请通过环境变量注入真实值");
        }
        return value;
    }
}
