package com.mall.admin.controller;

import com.mall.common.result.PageResult;
import com.mall.common.result.R;
import com.mall.core.model.dto.AfterSaleHandleDTO;
import com.mall.core.model.vo.AfterSaleVO;
import com.mall.core.service.AfterSaleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "后台售后")
@RestController
@RequestMapping("/api/v1/admin/after-sale")
@RequiredArgsConstructor
public class AfterSaleController {

    private final AfterSaleService afterSaleService;

    @Operation(summary = "售后单列表（status 可选：0 待处理 1 已退款 2 已拒绝 3 已撤销）")
    @GetMapping("/page")
    public R<PageResult<AfterSaleVO>> page(@RequestParam(defaultValue = "1") long pageNum,
                                           @RequestParam(defaultValue = "10") long pageSize,
                                           @RequestParam(required = false) Integer status) {
        return R.ok(afterSaleService.pageForAdmin(pageNum, pageSize, status));
    }

    @Operation(summary = "售后单详情")
    @GetMapping("/{id}")
    public R<AfterSaleVO> detail(@PathVariable Long id) {
        return R.ok(afterSaleService.detailForAdmin(id));
    }

    @Operation(summary = "同意并退款（退货退款会恢复库存）")
    @PostMapping("/{id}/approve")
    public R<Void> approve(@PathVariable Long id, @Valid @RequestBody(required = false) AfterSaleHandleDTO dto) {
        afterSaleService.approve(id, dto == null ? null : dto.getRemark());
        return R.ok(null);
    }

    @Operation(summary = "拒绝售后")
    @PostMapping("/{id}/reject")
    public R<Void> reject(@PathVariable Long id, @Valid @RequestBody(required = false) AfterSaleHandleDTO dto) {
        afterSaleService.reject(id, dto == null ? null : dto.getRemark());
        return R.ok(null);
    }
}
