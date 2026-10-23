package com.mall.core.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/** 后台首页看板：运营每天第一眼要看的几个数 */
@Data
@Schema(description = "后台看板概览")
public class DashboardSummaryVO {

    /** 今日 GMV（已支付口径，同报表） */
    private BigDecimal todayGmv;

    private long todayOrders;

    /** 待发货：实物 && 已支付 */
    private long pendingShip;

    /** 待处理售后 */
    private long pendingAfterSale;

    /** 待补发：虚拟订单已支付但没发出去（卡密不足） */
    private long pendingGrant;

    /** 低库存 SKU 数（可售 <= 阈值） */
    private long lowStockCount;

    /** 判定低库存用的阈值，前端展示用 */
    private int lowStockThreshold;
}
