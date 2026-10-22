package com.mall.core.job;

import com.mall.core.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 退款对账兜底任务。
 *
 * <p>为什么必须有它：退款成功与否**最终以渠道为准**，而渠道通知会丢
 * （网络抖动、回调地址配错、服务正好在重启）。只靠通知的话，微信那种
 * 「已受理（PROCESSING）」的退款会永远停在「退款中」——用户一直看不到钱，
 * 客服也不知道该不该重试。
 *
 * <p>这里每 5 分钟捞一批「退款中且 5 分钟没动过」的单，主动去渠道查一次。
 * 两个时间参数是刻意分开的：查询间隔短（5 分钟），但只查"够旧"的单，
 * 避免刚发起就查、把渠道正常处理中的单误判成异常。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mall.pay.refund-reconcile", name = "enabled", havingValue = "true")
public class RefundReconcileTask {

    private final PaymentService paymentService;

    @Scheduled(fixedDelayString = "${mall.pay.refund-reconcile.interval-millis:300000}")
    public void reconcile() {
        try {
            int changed = paymentService.reconcileProcessingRefunds(50);
            if (changed > 0) {
                log.info("[pay] refund reconcile task updated {} refunds", changed);
            }
        } catch (Exception e) {
            // 定时任务抛异常会被调度器吞掉并停止后续执行，这里必须自己接住
            log.warn("[pay] refund reconcile task failed: {}", e.toString());
        }
    }
}
