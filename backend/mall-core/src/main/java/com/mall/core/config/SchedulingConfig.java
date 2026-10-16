package com.mall.core.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 开启定时任务。具体任务由各自的 @ConditionalOnProperty 控制是否生效，
 * 因此两个应用都开 scheduling 也不会重复跑（超时关单只在 mall-admin 打开）。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
