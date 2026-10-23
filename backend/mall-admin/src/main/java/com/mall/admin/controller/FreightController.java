package com.mall.admin.controller;

import com.mall.common.result.R;
import com.mall.core.model.dto.FreightTemplateDTO;
import com.mall.core.model.vo.FreightTemplateVO;
import com.mall.core.service.FreightService;
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

@Tag(name = "后台-运费模板")
@RestController
@RequestMapping("/api/v1/admin/freight")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('freight:view')")
public class FreightController {

    private final FreightService freightService;

    @Operation(summary = "运费模板列表（status 可选）")
    @GetMapping
    public R<List<FreightTemplateVO>> list(@RequestParam(required = false) Integer status) {
        return R.ok(freightService.list(status));
    }

    @Operation(summary = "新建运费模板")
    @PostMapping
    @PreAuthorize("hasAuthority('freight:edit')")
    public R<Long> create(@Valid @RequestBody FreightTemplateDTO dto) {
        return R.ok(freightService.create(dto));
    }

    @Operation(summary = "修改运费模板")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('freight:edit')")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody FreightTemplateDTO dto) {
        freightService.update(id, dto);
        return R.ok();
    }

    @Operation(summary = "启用/停用 status=1|0")
    @PutMapping("/{id}/status")
    @PreAuthorize("hasAuthority('freight:edit')")
    public R<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        freightService.updateStatus(id, status);
        return R.ok();
    }

    @Operation(summary = "删除运费模板（还有商品在用则拒绝）")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('freight:edit')")
    public R<Void> delete(@PathVariable Long id) {
        freightService.delete(id);
        return R.ok();
    }
}
