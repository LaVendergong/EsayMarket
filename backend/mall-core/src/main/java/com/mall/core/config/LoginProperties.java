package com.mall.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 登录失败限流配置。默认 5 分钟内失败 5 次就锁 5 分钟。
 */
@Data
@Component
@ConfigurationProperties(prefix = "mall.security.login")
public class LoginProperties {

    /** 窗口内允许的最大失败次数；达到即拒绝继续尝试 */
    private int maxFailures = 5;

    /** 窗口长度（秒）。同时也是「锁定多久后自动解锁」的时长 */
    private int lockSeconds = 300;
}
