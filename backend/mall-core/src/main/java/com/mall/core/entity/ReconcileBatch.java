package com.mall.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mall.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 对账批次：一次"拿账单和本地记录比对"的汇总。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_reconcile_batch")
public class ReconcileBatch extends BaseEntity {

    private String channel;

    private LocalDate billDate;

    private Integer totalLines;

    private Integer matched;

    /** 渠道有、本地无 —— 最危险：钱收了但没记账 */
    private Integer missingLocal;

    /** 本地有、渠道无 —— 本地以为收钱了，其实没有 */
    private Integer missingChannel;

    private Integer amountMismatch;

    // ---- 退款侧计数（和支付分开，避免混在一起看不懂） ----
    private Integer refundTotalLines;

    private Integer refundMatched;

    private Integer refundMissingLocal;

    private Integer refundMissingChannel;

    private Integer refundAmountMismatch;

    /** 0 有差异 1 完全一致 */
    private Integer status;
}
