package com.mall.admin.controller;

import com.mall.common.result.PageResult;
import com.mall.common.result.R;
import com.mall.core.model.vo.ReviewVO;
import com.mall.core.audit.AuditAction;
import com.mall.core.service.AuditService;
import com.mall.core.service.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "后台-评价")
@RestController
@RequestMapping("/api/v1/admin/review")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('review:view')")
public class ReviewController {

    private final ReviewService reviewService;
    private final AuditService auditService;

    @Operation(summary = "评价分页（可按商品/状态筛选）")
    @GetMapping("/page")
    public R<PageResult<ReviewVO>> page(@RequestParam(defaultValue = "1") long pageNum,
                                        @RequestParam(defaultValue = "10") long pageSize,
                                        @RequestParam(required = false) Long productId,
                                        @RequestParam(required = false) Integer status) {
        return R.ok(reviewService.pageForAdmin(pageNum, pageSize, productId, status));
    }

    @Operation(summary = "隐藏/恢复评价 status=0|1（会重算商品评分）")
    @PutMapping("/{id}/status")
    @PreAuthorize("hasAuthority('review:handle')")
    public R<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        reviewService.updateStatus(id, status);
        // 隐藏评价会影响商品评分，属于「有争议的操作」，必须留痕
        auditService.record(status != null && status == 1 ? AuditAction.REVIEW_RESTORE : AuditAction.REVIEW_HIDE,
                "REVIEW", id, "status=" + status);
        return R.ok();
    }
}
