package com.mall.admin.controller;

import com.mall.common.result.PageResult;
import com.mall.common.result.R;
import com.mall.core.model.dto.CouponDTO;
import com.mall.core.model.vo.CouponRecordVO;
import com.mall.core.model.vo.CouponVO;
import com.mall.core.service.CouponService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "后台-优惠券")
@RestController
@RequestMapping("/api/v1/admin/coupon")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('coupon:view')")
public class CouponController {

    private final CouponService couponService;

    @Operation(summary = "券分页（含已停发，可按状态筛选/券名搜索）")
    @GetMapping("/page")
    public R<PageResult<CouponVO>> page(@RequestParam(defaultValue = "1") long pageNum,
                                        @RequestParam(defaultValue = "10") long pageSize,
                                        @RequestParam(required = false) Integer status,
                                        @RequestParam(required = false) String keyword) {
        return R.ok(couponService.pageForAdmin(pageNum, pageSize, status, keyword));
    }

    @Operation(summary = "券的领取明细（谁领了、用没用、用在哪一单）")
    @GetMapping("/{id}/records")
    public R<List<CouponRecordVO>> records(@PathVariable Long id,
                                          @RequestParam(defaultValue = "200") int limit) {
        return R.ok(couponService.records(id, limit));
    }

    @Operation(summary = "新建优惠券")
    @PostMapping
    @PreAuthorize("hasAuthority('coupon:edit')")
    public R<Long> create(@Valid @RequestBody CouponDTO dto) {
        return R.ok(couponService.create(dto));
    }

    @Operation(summary = "修改优惠券（发放总量不能小于已领取数）")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('coupon:edit')")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody CouponDTO dto) {
        couponService.update(id, dto);
        return R.ok();
    }

    @Operation(summary = "停发/恢复 status=0|1（已领到的券仍可用）")
    @PutMapping("/{id}/status")
    @PreAuthorize("hasAuthority('coupon:edit')")
    public R<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        couponService.updateStatus(id, status);
        return R.ok();
    }

    @Operation(summary = "删除优惠券（已被领取过的只能停发）")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('coupon:edit')")
    public R<Void> delete(@PathVariable Long id) {
        couponService.delete(id);
        return R.ok();
    }
}
