package com.mall.admin.controller;

import com.mall.common.result.PageResult;
import com.mall.common.result.R;
import com.mall.core.model.dto.ProductDTO;
import com.mall.core.model.vo.ProductDetailVO;
import com.mall.core.model.vo.ProductVO;
import com.mall.core.service.ProductService;
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
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "后台-商品")
@RestController
@RequestMapping("/api/v1/admin/product")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @Operation(summary = "商品分页（含下架，可按状态筛选）")
    @GetMapping("/page")
    public R<PageResult<ProductVO>> page(@RequestParam(defaultValue = "1") long pageNum,
                                         @RequestParam(defaultValue = "10") long pageSize,
                                         @RequestParam(required = false) Long categoryId,
                                         @RequestParam(required = false) String keyword,
                                         @RequestParam(required = false) Integer status) {
        return R.ok(productService.pageForAdmin(pageNum, pageSize, categoryId, keyword, status));
    }

    @Operation(summary = "商品详情（含 SKU）")
    @GetMapping("/{id}")
    public R<ProductDetailVO> detail(@PathVariable Long id) {
        return R.ok(productService.detailForAdmin(id));
    }

    @Operation(summary = "新增商品（SPU + SKU 一次提交）")
    @PostMapping
    public R<Long> create(@Valid @RequestBody ProductDTO dto) {
        return R.ok(productService.create(dto));
    }

    @Operation(summary = "修改商品（SKU 按 id 对齐：有则改、无则增、缺则删）")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody ProductDTO dto) {
        productService.update(id, dto);
        return R.ok();
    }

    @Operation(summary = "上架/下架 status=0|1")
    @PutMapping("/{id}/status")
    public R<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        productService.updateStatus(id, status);
        return R.ok();
    }

    @Operation(summary = "删除商品（逻辑删除，连同其 SKU）")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        productService.delete(id);
        return R.ok();
    }
}
