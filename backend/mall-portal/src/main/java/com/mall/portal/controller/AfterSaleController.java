package com.mall.portal.controller;

import com.mall.common.result.PageResult;
import com.mall.common.result.R;
import com.mall.core.model.dto.AfterSaleApplyDTO;
import com.mall.core.model.vo.AfterSaleVO;
import com.mall.core.security.UserContext;
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

@Tag(name = "售后")
@RestController
@RequestMapping("/api/v1/portal/after-sale")
@RequiredArgsConstructor
public class AfterSaleController {

    private final AfterSaleService afterSaleService;

    @Operation(summary = "申请售后（仅退款 / 退货退款）")
    @PostMapping
    public R<Long> apply(@Valid @RequestBody AfterSaleApplyDTO dto) {
        return R.ok(afterSaleService.apply(UserContext.requireUserId(), dto));
    }

    @Operation(summary = "我的售后列表")
    @GetMapping("/page")
    public R<PageResult<AfterSaleVO>> page(@RequestParam(defaultValue = "1") long pageNum,
                                           @RequestParam(defaultValue = "10") long pageSize) {
        return R.ok(afterSaleService.pageForUser(UserContext.requireUserId(), pageNum, pageSize));
    }

    @Operation(summary = "售后详情")
    @GetMapping("/{id}")
    public R<AfterSaleVO> detail(@PathVariable Long id) {
        return R.ok(afterSaleService.detailForUser(UserContext.requireUserId(), id));
    }

    /** 订单详情页用它判断「能不能申请售后 / 现在是什么状态」 */
    @Operation(summary = "某订单最近一条售后单（没有则 data 为 null）")
    @GetMapping("/by-order/{orderId}")
    public R<AfterSaleVO> byOrder(@PathVariable Long orderId) {
        return R.ok(afterSaleService.latestOfOrder(UserContext.requireUserId(), orderId));
    }

    @Operation(summary = "撤销售后申请（仅待商家处理时）")
    @PostMapping("/{id}/cancel")
    public R<Void> cancel(@PathVariable Long id) {
        afterSaleService.cancel(UserContext.requireUserId(), id);
        return R.ok(null);
    }
}
