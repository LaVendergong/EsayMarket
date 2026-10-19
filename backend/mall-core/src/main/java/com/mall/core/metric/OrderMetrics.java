package com.mall.core.metric;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 下单链路的业务指标（Prometheus 抓取）。
 *
 * <p><b>为什么需要</b>：只有 <code>/actuator/health</code> 时，出问题只能看到「进程还活着」，
 * 看不到「业务健不健康」。这两个计数器的实际用途：
 * <ul>
 *   <li>{@code mall_orders_created_total} 在营业时段掉到 0 → 下单链路断了（依赖挂了 / 接口报错），
 *       这条告警比「CPU 高」有用得多</li>
 *   <li>{@code mall_orders_stock_shortage_total} 陡增 → 库存被抢空，或有人在刷单</li>
 * </ul>
 * 典型告警规则：{@code rate(mall_orders_created_total[5m]) == 0}（营业时段）、
 * {@code rate(mall_orders_stock_shortage_total[5m]) > 10}。
 *
 * <p>计数器在构造时一次性注册（Micrometer 内部按 name+tags 复用实例，重复 register 不会重复计数）。
 */
@Component
public class OrderMetrics {

    private final Counter created;
    private final Counter stockShortage;

    public OrderMetrics(MeterRegistry registry) {
        this.created = Counter.builder("mall.orders.created")
                .description("成功创建的订单数")
                .register(registry);
        this.stockShortage = Counter.builder("mall.orders.stock_shortage")
                .description("因库存不足被回滚的下单次数")
                .register(registry);
    }

    /** 订单落库成功（同一 requestId 的幂等重放**不算**，因为它并没有真的建单） */
    public void orderCreated() {
        created.increment();
    }

    /** 条件 UPDATE 锁库存失败 → 整单回滚 */
    public void stockShortage() {
        stockShortage.increment();
    }
}
