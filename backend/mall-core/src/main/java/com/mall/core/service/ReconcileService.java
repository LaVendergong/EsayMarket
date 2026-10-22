package com.mall.core.service;

import com.mall.common.result.PageResult;
import com.mall.core.model.vo.ReconcileBatchVO;

import java.time.LocalDate;

public interface ReconcileService {

    /** 拿某渠道某天的账单和本地支付记录逐笔比对，落库并返回汇总 */
    ReconcileBatchVO reconcile(String channel, LocalDate billDate, String content);

    PageResult<ReconcileBatchVO> page(long pageNum, long pageSize);

    /** 详情：带逐笔差异清单 */
    ReconcileBatchVO detail(Long batchId);

    /**
     * 处理一条差异（已核实 / 忽略），记审计。
     *
     * <p>只改差异的归档状态，**不做任何资金操作** —— 补记与重退仍走
     * 「同步支付」「查询退款」接口。
     */
    ReconcileBatchVO handleDiff(Long diffId, String action, String remark, String operator);
}
