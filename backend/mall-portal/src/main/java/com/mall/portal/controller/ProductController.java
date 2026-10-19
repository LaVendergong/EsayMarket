package com.mall.portal.controller;

import com.mall.common.result.PageResult;
import com.mall.common.result.R;
import com.mall.core.model.vo.ProductDetailVO;
import com.mall.core.model.vo.ProductVO;
import com.mall.core.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@Tag(name = "商品")
@RestController
@RequestMapping("/api/v1/portal/product")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @Operation(summary = "商品分页（仅上架；支持分类/关键词/价格区间/排序，匿名可访问）")
    @GetMapping("/page")
    public R<PageResult<ProductVO>> page(@RequestParam(defaultValue = "1") long pageNum,
                                         @RequestParam(defaultValue = "10") long pageSize,
                                         @RequestParam(required = false) Long categoryId,
                                         @RequestParam(required = false) String keyword,
                                         @RequestParam(required = false) BigDecimal minPrice,
                                         @RequestParam(required = false) BigDecimal maxPrice,
                                         @RequestParam(required = false) String sort) {
        return R.ok(productService.pageForPortal(pageNum, pageSize, categoryId, keyword, minPrice, maxPrice, sort));
    }

    @Operation(summary = "商品详情（含 SKU，匿名可访问）")
    @GetMapping("/{id}")
    public R<ProductDetailVO> detail(@PathVariable Long id) {
        return R.ok(productService.detailForPortal(id));
    }
}
