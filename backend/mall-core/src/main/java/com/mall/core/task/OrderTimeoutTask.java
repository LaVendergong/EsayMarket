package com.mall.core.task;

import com.mall.core.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 超时关单：周期性关闭 expire_time 已过且仍待支付的订单，并释放锁定库存。
 * 只在配置了 mall.order.timeout-job.enabled=true 的应用里生效（本项目是 mall-admin）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mall.order.timeout-job", name = "enabled", havingValue = "true")
public class OrderTimeoutTask {

    private final OrderService orderService;

    @Scheduled(fixedDelayString = "${mall.order.timeout-job.interval-millis:60000}")
    public void closeExpiredOrders() {
        try {
            int closed = orderService.closeExpiredOrders(100);
            if (closed > 0) {
                log.info("[超时关单] 关闭 {} 笔超时未支付订单", closed);
            }
        } catch (Exception e) {
            log.error("[超时关单] 执行异常", e);
        }
    }
}
