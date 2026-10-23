package com.mall.core.task;

import com.mall.core.metric.OrderMetrics;
import com.mall.core.service.DigitalGoodsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 卡密池对账：可售库存（stock - locked）必须等于池子里的可用卡数。
 *
 * <p>两者本应永远相等（导入加库存、下单锁库存、支付扣库存并从池子取卡）。
 * 一旦不相等，说明有人绕过接口改库，或发放时条件 UPDATE 没命中 —— 继续卖会发不出东西。
 * 这里只**发现并告警**，不自动修数：自动把 stock 改小/改大都会掩盖真正的原因。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mall.cardkey.reconcile-job", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class CardKeyReconcileTask {

    private final DigitalGoodsService digitalGoodsService;
    private final OrderMetrics orderMetrics;

    @Scheduled(fixedDelayString = "${mall.cardkey.reconcile-job.interval-millis:3600000}")
    public void reconcile() {
        try {
            long mismatched = digitalGoodsService.checkStockConsistency();
            orderMetrics.cardKeyMismatch((int) mismatched);
        } catch (Exception e) {
            log.error("[cardkey-job] reconcile failed", e);
        }
    }
}
