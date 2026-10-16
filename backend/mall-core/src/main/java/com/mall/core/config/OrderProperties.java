package com.mall.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "mall.order")
public class OrderProperties {

    /** 未支付订单的有效期（分钟），下单时写入 t_order.expire_time */
    private long expireMinutes = 30;

    private TimeoutJob timeoutJob = new TimeoutJob();

    @Data
    public static class TimeoutJob {

        /** 是否开启超时关单定时任务。只在 mall-admin 打开，避免两个应用重复扫同一批订单 */
        private boolean enabled = false;

        /** 扫描间隔（毫秒） */
        private long intervalMillis = 60000L;
    }
}
