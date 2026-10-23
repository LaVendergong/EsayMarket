package com.mall.admin.controller;

import com.mall.common.result.R;
import com.mall.core.model.vo.MaintenanceVO;
import com.mall.core.service.MaintenanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "后台-数据维护")
@RestController
@RequestMapping("/api/v1/admin/maintenance")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('maintenance:view')")
public class MaintenanceController {

    private final MaintenanceService maintenanceService;

    @Operation(summary = "各表行数 + 搜索日志清理状态")
    @GetMapping("/stats")
    public R<MaintenanceVO> stats() {
        return R.ok(maintenanceService.stats());
    }
}
