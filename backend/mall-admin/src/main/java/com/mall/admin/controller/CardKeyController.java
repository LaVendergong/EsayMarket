package com.mall.admin.controller;

import com.mall.common.result.R;
import com.mall.core.audit.AuditAction;
import com.mall.core.service.AuditService;
import com.mall.core.service.DigitalGoodsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "后台-卡密")
@RestController
@RequestMapping("/api/v1/admin/card-key")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('card-key:import')")
public class CardKeyController {

    private final DigitalGoodsService digitalGoodsService;
    private final AuditService auditService;

    @Data
    public static class ImportDTO {
        private Long skuId;
        /** 每行「卡号,卡密」，逗号或 Tab 分隔；也可以只有卡号 */
        private List<String> lines;
    }

    @Operation(summary = "导入卡密（导多少张就加多少可售库存）")
    @PostMapping("/import")
    public R<Integer> importKeys(@RequestBody ImportDTO dto) {
        int imported = digitalGoodsService.importCardKeys(dto.getSkuId(), dto.getLines());
        // 卡密就是钱：谁在什么时候往哪个 SKU 里导了多少张，必须可追溯
        auditService.record(AuditAction.CARD_KEY_IMPORT, "SKU", dto.getSkuId(), "imported=" + imported);
        return R.ok(imported);
    }

    @Operation(summary = "某 SKU 剩余可用卡密数（与 SKU 库存对账用）")
    @GetMapping("/count")
    public R<Long> count(@RequestParam Long skuId) {
        return R.ok(digitalGoodsService.availableCount(skuId));
    }

    @Operation(summary = "全局对账：卡密池可用数与可售库存不一致的 SKU 数量（0 = 健康）")
    @GetMapping("/consistency")
    public R<Long> consistency() {
        return R.ok(digitalGoodsService.checkStockConsistency());
    }
}
