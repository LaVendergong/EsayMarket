package com.mall.admin.controller;

import com.mall.common.result.R;
import com.mall.core.model.vo.DashboardSummaryVO;
import com.mall.core.model.vo.LowStockVO;
import com.mall.core.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "后台-看板")
@RestController
@RequestMapping("/api/v1/admin/dashboard")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('dashboard:view')")
public class DashboardController {

    private final DashboardService dashboardService;

    @Operation(summary = "首页概览：今日 GMV/订单、待发货、待售后、待补发、低库存")
    @GetMapping("/summary")
    public R<DashboardSummaryVO> summary() {
        return R.ok(dashboardService.summary());
    }

    @Operation(summary = "低库存 SKU 明细")
    @GetMapping("/low-stock")
    public R<List<LowStockVO>> lowStock(@RequestParam(defaultValue = "10") int limit) {
        return R.ok(dashboardService.lowStock(limit));
    }
}
