package com.mall.admin.controller;

import com.mall.common.result.R;
import com.mall.core.model.vo.DailyStatVO;
import com.mall.core.model.vo.ReportSummaryVO;
import com.mall.core.model.vo.TopProductVO;
import com.mall.core.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "后台-销售报表")
@RestController
@RequestMapping("/api/v1/admin/report")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('report:view')")
public class ReportController {

    private final ReportService reportService;

    @Operation(summary = "销售概览（近 N 天，含今天）")
    @GetMapping("/summary")
    public R<ReportSummaryVO> summary(@RequestParam(defaultValue = "7") int days) {
        return R.ok(reportService.summary(days));
    }

    @Operation(summary = "按天统计")
    @GetMapping("/daily")
    public R<List<DailyStatVO>> daily(@RequestParam(defaultValue = "7") int days) {
        return R.ok(reportService.daily(days));
    }

    @Operation(summary = "热销商品 Top N")
    @GetMapping("/top-products")
    public R<List<TopProductVO>> topProducts(@RequestParam(defaultValue = "7") int days,
                                             @RequestParam(defaultValue = "10") int limit) {
        return R.ok(reportService.topProducts(days, limit));
    }
}
