package com.mall.core.task;

import com.mall.core.service.CouponService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 优惠券过期：把「券模板已过有效期、用户券还未使用」的记录置为已过期（0 → 2）。
 *
 * <p>为什么需要定时扫而不是只在查询时判定：过期只在读取时算的话，库里状态永远是 0，
 * 后台统计、用户列表都显示「未使用」，用户点了「去使用」才被计价接口拒绝。
 *
 * <p>多实例同时跑是安全的：走的是带 {@code status = 0} 条件的 UPDATE，重复执行只会命中 0 行。
 * 默认两端口都开（同 OrderTimeoutTask 的理由：单点挂了脏数据就一直在）。
 * 关掉：{@code mall.coupon.expire-job.enabled=false}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mall.coupon.expire-job", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class CouponExpireTask {

    private final CouponService couponService;

    /** 默认一小时一次：券的有效期只精确到天，扫得太勤没有意义 */
    @Scheduled(fixedDelayString = "${mall.coupon.expire-job.interval-millis:3600000}")
    public void expireOutdated() {
        try {
            couponService.expireOutdated();
        } catch (Exception e) {
            log.error("[coupon-expire-job] scan failed", e);
        }
    }
}
