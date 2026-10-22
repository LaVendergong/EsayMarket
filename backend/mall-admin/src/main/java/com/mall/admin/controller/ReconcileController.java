package com.mall.admin.controller;

import com.mall.common.result.PageResult;
import com.mall.common.result.R;
import com.mall.core.model.dto.ReconcileRequestDTO;
import com.mall.core.model.vo.ReconcileBatchVO;
import com.mall.core.service.ReconcileService;
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

@Tag(name = "后台对账")
@RestController
@RequestMapping("/api/v1/admin/reconcile")
@RequiredArgsConstructor
public class ReconcileController {

    private final ReconcileService reconcileService;

    /**
     * 上传某渠道某天的账单，和本地支付记录逐笔比对。
     *
     * <p>用「上传账单文件」而不是「自动下载」：真实账单接口需要商户资质，而且账单格式
     * 必须拿真实文件核对过才敢自动化。这一版把控住**比对逻辑**这个风险最高的部分。
     */
    @Operation(summary = "账单对账（上传账单内容）")
    @PostMapping
    public R<ReconcileBatchVO> reconcile(@Valid @RequestBody ReconcileRequestDTO dto) {
        return R.ok(reconcileService.reconcile(dto.getChannel(), dto.getBillDate(), dto.getContent()));
    }

    @Operation(summary = "对账批次列表")
    @GetMapping("/page")
    public R<PageResult<ReconcileBatchVO>> page(@RequestParam(defaultValue = "1") long pageNum,
                                                @RequestParam(defaultValue = "10") long pageSize) {
        return R.ok(reconcileService.page(pageNum, pageSize));
    }

    @Operation(summary = "对账详情（含逐笔差异）")
    @GetMapping("/{id}")
    public R<ReconcileBatchVO> detail(@PathVariable Long id) {
        return R.ok(reconcileService.detail(id));
    }
}
