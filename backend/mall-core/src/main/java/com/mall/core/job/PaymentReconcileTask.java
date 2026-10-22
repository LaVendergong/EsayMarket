package com.mall.core.job;

import com.mall.core.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 支付对账兜底任务：把「用户付了钱、但回调丢了」的订单救回来。
 *
 * <p>没有它的话，这种情况只能等订单超时被关掉 —— 钱扣了、单关了，是最难解释的一类事故。
 *
 * <p>只捞「待支付且创建超过 2 分钟」的单：用户刚点完支付正在跳转，这时候查一定是未支付，
 * 白打一次接口。真正的回调延迟通常只有几秒。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mall.pay.payment-reconcile", name = "enabled", havingValue = "true")
public class PaymentReconcileTask {

    private final PaymentService paymentService;

    @Scheduled(fixedDelayString = "${mall.pay.payment-reconcile.interval-millis:120000}")
    public void reconcile() {
        try {
            int rescued = paymentService.reconcilePendingPayments(50);
            if (rescued > 0) {
                log.info("[pay] payment reconcile task rescued {} payments", rescued);
            }
        } catch (Exception e) {
            // 定时任务抛异常会被调度器吞掉并可能停止后续调度，必须自己接住
            log.warn("[pay] payment reconcile task failed: {}", e.toString());
        }
    }
}
