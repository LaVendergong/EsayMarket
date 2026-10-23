package com.mall.core.service.impl;

import com.mall.core.mapper.DashboardMapper;
import com.mall.core.model.vo.DashboardSummaryVO;
import com.mall.core.model.vo.LowStockVO;
import com.mall.core.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private final DashboardMapper dashboardMapper;

    /** 可售 <= 这个值就算低库存。虚拟卡密商品为 0 时同样会命中（提醒去导入卡密） */
    @Value("${mall.stock.warn-threshold:5}")
    private int warnThreshold;

    @Override
    public DashboardSummaryVO summary() {
        LocalDateTime today = LocalDate.now().atStartOfDay();
        DashboardSummaryVO vo = new DashboardSummaryVO();
        vo.setTodayGmv(dashboardMapper.gmvSince(today));
        vo.setTodayOrders(dashboardMapper.ordersSince(today));
        vo.setPendingShip(dashboardMapper.pendingShip());
        vo.setPendingAfterSale(dashboardMapper.pendingAfterSale());
        vo.setPendingGrant(dashboardMapper.pendingGrant());
        vo.setLowStockCount(dashboardMapper.lowStockCount(warnThreshold));
        vo.setLowStockThreshold(warnThreshold);
        return vo;
    }

    @Override
    public List<LowStockVO> lowStock(int limit) {
        int n = Math.min(Math.max(limit, 1), 50);
        return dashboardMapper.lowStock(warnThreshold, n);
    }
}
