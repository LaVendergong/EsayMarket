package com.mall.portal.controller;

import com.mall.common.result.R;
import com.mall.core.model.vo.CouponVO;
import com.mall.core.model.vo.UserCouponVO;
import com.mall.core.security.UserContext;
import com.mall.core.service.CouponService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "优惠券")
@RestController
@RequestMapping("/api/v1/portal/coupon")
@RequiredArgsConstructor
public class CouponController {

    private final CouponService couponService;

    @Operation(summary = "领券中心（含「我是否已领」）")
    @GetMapping("/center")
    public R<List<CouponVO>> center() {
        return R.ok(couponService.center(UserContext.requireUserId()));
    }

    @Operation(summary = "领取优惠券（每人每券限一张）")
    @PostMapping("/{id}/receive")
    public R<Void> receive(@PathVariable Long id) {
        couponService.receive(UserContext.requireUserId(), id);
        return R.ok();
    }

    @Operation(summary = "我的券（status：0 未使用 1 已使用 2 已过期，不传=全部）")
    @GetMapping("/mine")
    public R<List<UserCouponVO>> mine(@RequestParam(required = false) Integer status) {
        return R.ok(couponService.mine(UserContext.requireUserId(), status));
    }
}
