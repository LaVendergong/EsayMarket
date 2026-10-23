package com.mall.portal.controller;

import com.mall.common.result.PageResult;
import com.mall.common.result.R;
import com.mall.core.model.dto.ReviewDTO;
import com.mall.core.model.vo.ReviewVO;
import com.mall.core.security.UserContext;
import com.mall.core.service.ReviewService;
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

import java.util.List;

@Tag(name = "商品评价")
@RestController
@RequestMapping("/api/v1/portal/review")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @Operation(summary = "提交评价（订单项维度，只能评一次）")
    @PostMapping
    public R<Void> submit(@Valid @RequestBody ReviewDTO dto) {
        reviewService.submit(UserContext.requireUserId(), dto);
        return R.ok();
    }

    @Operation(summary = "商品评价列表（匿名可访问）")
    @GetMapping("/product/{productId}")
    public R<PageResult<ReviewVO>> page(@PathVariable Long productId,
                                        @RequestParam(defaultValue = "1") long pageNum,
                                        @RequestParam(defaultValue = "10") long pageSize) {
        return R.ok(reviewService.pageForProduct(productId, pageNum, pageSize));
    }

    @Operation(summary = "该订单里已评价的订单项 id")
    @GetMapping("/order/{orderId}/reviewed")
    public R<List<Long>> reviewed(@PathVariable Long orderId) {
        return R.ok(reviewService.reviewedItemIds(UserContext.requireUserId(), orderId));
    }
}
