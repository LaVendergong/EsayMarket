package com.mall.admin.controller;

import com.mall.common.result.R;
import com.mall.core.model.dto.CategoryDTO;
import com.mall.core.model.vo.CategoryVO;
import com.mall.core.service.CategoryService;
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

import java.util.List;

@Tag(name = "后台-商品分类")
@RestController
@RequestMapping("/api/v1/admin/category")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @Operation(summary = "分类树（含禁用）")
    @GetMapping("/tree")
    public R<List<CategoryVO>> tree() {
        return R.ok(categoryService.tree(null));
    }

    @Operation(summary = "新增分类")
    @PostMapping
    public R<Long> create(@Valid @RequestBody CategoryDTO dto) {
        return R.ok(categoryService.create(dto));
    }

    @Operation(summary = "修改分类")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody CategoryDTO dto) {
        categoryService.update(id, dto);
        return R.ok();
    }

    @Operation(summary = "删除分类（有子分类或商品时拒绝）")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return R.ok();
    }

    @Operation(summary = "启用/禁用分类 status=0|1")
    @PutMapping("/{id}/status")
    public R<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        categoryService.updateStatus(id, status);
        return R.ok();
    }
}
