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
import java.util.List;

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

    @Operation(summary = "搜索热词（近 7 天，匿名可访问）")
    @GetMapping("/hot-keywords")
    public R<List<String>> hotKeywords(@RequestParam(defaultValue = "10") int limit) {
        return R.ok(productService.hotKeywords(limit));
    }

    @Operation(summary = "搜索联想（分类名 + 商品名，匿名可访问）")
    @GetMapping("/suggest")
    public R<List<String>> suggest(@RequestParam String keyword,
                                   @RequestParam(defaultValue = "10") int limit) {
        return R.ok(productService.suggest(keyword, limit));
    }

    @Operation(summary = "商品详情（含 SKU，匿名可访问）")
    @GetMapping("/{id}")
    public R<ProductDetailVO> detail(@PathVariable Long id) {
        return R.ok(productService.detailForPortal(id));
    }
}
