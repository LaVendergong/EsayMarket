package com.mall.core.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 开启定时任务。具体任务由各自的 @ConditionalOnProperty 控制是否生效。
 *
 * <p>超时关单默认在两个应用都开，靠订单状态的条件 UPDATE 保证「同一单只会被关一次」，
 * 所以这里的重复调度是安全的（理由见 OrderTimeoutTask 的类注释）。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
