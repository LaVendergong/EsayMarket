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

        log.info("[prod 启动自检] 通过：JWT 密钥与数据库口令均已从环境变量注入");
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
