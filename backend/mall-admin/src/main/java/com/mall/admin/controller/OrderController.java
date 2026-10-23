package com.mall.admin.controller;

import com.mall.common.result.PageResult;
import com.mall.common.result.R;
import com.mall.core.audit.AuditAction;
import com.mall.core.model.dto.ShipDTO;
import com.mall.core.model.vo.OrderVO;
import com.mall.core.service.AuditService;
import com.mall.core.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

@Tag(name = "后台-订单")
@RestController
@RequestMapping("/api/v1/admin/order")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('order:view')")
public class OrderController {

    private final OrderService orderService;
    private final AuditService auditService;

    @Operation(summary = "订单分页（status 可选，keyword 按订单号模糊）")
    @GetMapping("/page")
    public R<PageResult<OrderVO>> page(@RequestParam(defaultValue = "1") long pageNum,
                                      @RequestParam(defaultValue = "10") long pageSize,
                                      @RequestParam(required = false) Integer status,
                                      @RequestParam(required = false) String keyword) {
        return R.ok(orderService.pageForAdmin(pageNum, pageSize, status, keyword));
    }

    @Operation(summary = "导出订单 CSV（按当前筛选，最多 5000 行）")
    @GetMapping("/export")
    @PreAuthorize("hasAuthority('order:export')")
    public void export(@RequestParam(required = false) Integer status,
                       @RequestParam(required = false) String keyword,
                       @RequestParam(defaultValue = "5000") int limit,
                       HttpServletResponse response) throws IOException {
        // 先把内容算完再设响应头：算出错时还能走全局异常处理返回 JSON
        String csv = orderService.exportCsv(status, keyword, limit);
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"orders-" + LocalDate.now() + ".csv\"");
        response.getOutputStream().write(csv.getBytes(StandardCharsets.UTF_8));
    }

    @Operation(summary = "订单详情（含明细）")
    @GetMapping("/{id}")
    public R<OrderVO> detail(@PathVariable Long id) {
        return R.ok(orderService.detailForAdmin(id));
    }

    @Operation(summary = "发货：已支付 -> 已发货（必须带快递公司与运单号）")
    @PostMapping("/{id}/ship")
    @PreAuthorize("hasAuthority('order:ship')")
    public R<Void> ship(@PathVariable Long id, @Valid @RequestBody ShipDTO dto) {
        orderService.ship(id, dto.getCompany(), dto.getShipNo());
        auditService.record(AuditAction.ORDER_SHIP, "ORDER", id,
                "company=" + dto.getCompany() + ", shipNo=" + dto.getShipNo());
        return R.ok();
    }

    @Operation(summary = "虚拟商品补发（支付成功但发放失败时用）")
    @PostMapping("/{id}/grant")
    @PreAuthorize("hasAuthority('order:grant')")
    public R<Void> regrant(@PathVariable Long id) {
        orderService.regrantDigital(id);
        auditService.record(AuditAction.ORDER_GRANT, "ORDER", id, null);
        return R.ok();
    }

    @Operation(summary = "关闭订单：待支付 -> 已关闭（释放库存）")
    @PostMapping("/{id}/close")
    @PreAuthorize("hasAuthority('order:close')")
    public R<Void> close(@PathVariable Long id) {
        orderService.close(id);
        auditService.record(AuditAction.ORDER_CLOSE, "ORDER", id, null);
        return R.ok();
    }
}
