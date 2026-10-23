package com.mall.core.service;

import com.mall.core.model.vo.DashboardSummaryVO;
import com.mall.core.model.vo.LowStockVO;

import java.util.List;

/** 后台首页看板 */
public interface DashboardService {

    DashboardSummaryVO summary();

    /** 低库存明细（默认只看最紧急的 10 条） */
    List<LowStockVO> lowStock(int limit);
}
