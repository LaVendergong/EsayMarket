package com.mall.portal.controller;

import com.mall.common.result.PageResult;
import com.mall.common.result.R;
import com.mall.core.model.dto.CreateDirectOrderDTO;
import com.mall.core.model.dto.CreateOrderDTO;
import com.mall.core.model.vo.OrderCreateVO;
import com.mall.core.model.vo.OrderVO;
import com.mall.core.security.UserContext;
import com.mall.core.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "订单")
@RestController
@RequestMapping("/api/v1/portal/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @Operation(summary = "结算购物车选中项下单（addressId 不传用默认地址）")
    @PostMapping
    public R<OrderCreateVO> create(@RequestBody(required = false) CreateOrderDTO dto) {
        return R.ok(orderService.create(UserContext.requireUserId(), dto));
    }

    @Operation(summary = "「立即购买」直连下单（指定 SKU + 数量，不经过购物车）")
    @PostMapping("/direct")
    public R<OrderCreateVO> createDirect(@RequestBody CreateDirectOrderDTO dto) {
        return R.ok(orderService.createDirect(UserContext.requireUserId(), dto));
    }

    @Operation(summary = "我的订单分页（status 可选）")
    @GetMapping("/page")
    public R<PageResult<OrderVO>> page(@RequestParam(defaultValue = "1") long pageNum,
                                      @RequestParam(defaultValue = "10") long pageSize,
                                      @RequestParam(required = false) Integer status) {
        return R.ok(orderService.pageByUser(UserContext.requireUserId(), pageNum, pageSize, status));
    }

    @Operation(summary = "订单详情（含明细）")
    @GetMapping("/{id}")
    public R<OrderVO> detail(@PathVariable Long id) {
        return R.ok(orderService.detail(UserContext.requireUserId(), id));
    }

    @Operation(summary = "模拟支付：待支付 -> 已支付（扣减库存）")
    @PostMapping("/{id}/pay")
    public R<Void> pay(@PathVariable Long id) {
        orderService.pay(UserContext.requireUserId(), id);
        return R.ok();
    }

    @Operation(summary = "取消订单：待支付 -> 已关闭（释放库存）")
    @PostMapping("/{id}/cancel")
    public R<Void> cancel(@PathVariable Long id) {
        orderService.cancel(UserContext.requireUserId(), id);
        return R.ok();
    }

    @Operation(summary = "确认收货：已发货 -> 已完成")
    @PostMapping("/{id}/confirm")
    public R<Void> confirm(@PathVariable Long id) {
        orderService.confirm(UserContext.requireUserId(), id);
        return R.ok();
    }
}
