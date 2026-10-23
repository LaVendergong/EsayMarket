package com.mall.admin.controller;

import com.mall.common.result.PageResult;
import com.mall.common.result.R;
import com.mall.core.model.vo.AdminAuditLogVO;
import com.mall.core.service.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "后台-操作审计")
@RestController
@RequestMapping("/api/v1/admin/audit")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('audit:view')")
public class AuditController {

    private final AuditService auditService;

    @Operation(summary = "操作审计分页（可按动作筛选）")
    @GetMapping("/page")
    public R<PageResult<AdminAuditLogVO>> page(@RequestParam(defaultValue = "1") long pageNum,
                                               @RequestParam(defaultValue = "20") long pageSize,
                                               @RequestParam(required = false) String action) {
        return R.ok(auditService.page(pageNum, pageSize, action));
    }
}
