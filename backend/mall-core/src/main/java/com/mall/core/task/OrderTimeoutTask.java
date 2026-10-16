package com.mall.core.task;

import com.mall.core.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 超时关单：周期性关闭 expire_time 已过且仍待支付的订单，并释放锁定库存。
 *
 * <p><b>默认 mall-admin 与 mall-portal 都开启</b>。原因是单点运行有真实风险：只在一个应用里跑，
 * 它挂了（或没启动）超时订单就永远不关、锁定库存永远不释放。
 *
 * <p>多个实例同时扫同一批订单是**安全**的：关单走的是「带 from 状态的条件 UPDATE」，
 * 只有把 status 从 0 改成 4 成功的那一个实例才会去释放库存，另一个拿到 0 行直接跳过
 * （见 {@code OrderServiceImplTest#closeExpiredOrders_skipsOrdersSomeoneElseAlreadyHandled}）。
 * 代价只是几次空扫描，所以宁可多处跑也不要单点。
 *
 * <p>只想让一个应用跑，就把另一边的 {@code mall.order.timeout-job.enabled} 设为 false
 * （例如多副本部署时只给一个副本开）。间隔由 {@code mall.order.timeout-job.interval-millis} 控制。
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
                // 这行是唯一会被自动化脚本解析的日志（scripts\timeout-job-verify.ps1 用它断言
                // 「同一单只会被关一次」），所以刻意保持 ASCII 且格式稳定，别改前缀与措辞。
                log.info("[timeout-job] closed {} expired unpaid order(s)", closed);
            }
        } catch (Exception e) {
            log.error("[timeout-job] scan failed", e);
        }
    }
}
