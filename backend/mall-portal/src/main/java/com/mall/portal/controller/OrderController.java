package com.mall.portal.controller;

import com.mall.common.result.PageResult;
import com.mall.common.result.R;
import com.mall.core.model.dto.CreateDirectOrderDTO;
import com.mall.core.model.dto.CreateOrderDTO;
import com.mall.core.model.vo.OrderCreateVO;
import com.mall.core.model.vo.OrderVO;
import com.mall.core.security.UserContext;
import com.mall.core.service.OrderService;
import com.mall.core.service.PaymentService;
import com.mall.core.tx.DeadlockRetry;
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
    private final PaymentService paymentService;

    @Operation(summary = "结算购物车选中项下单（addressId 不传用默认地址）")
    @PostMapping
    public R<OrderCreateVO> create(@RequestBody(required = false) CreateOrderDTO dto) {
        Long userId = UserContext.requireUserId();
        // 高并发下 MySQL 会主动 kill 死锁的事务，重试是标准做法（下单本身靠 requestId 幂等）
        return R.ok(DeadlockRetry.run(() -> orderService.create(userId, dto)));
    }

    @Operation(summary = "「立即购买」直连下单（指定 SKU + 数量，不经过购物车）")
    @PostMapping("/direct")
    public R<OrderCreateVO> createDirect(@RequestBody CreateDirectOrderDTO dto) {
        Long userId = UserContext.requireUserId();
        return R.ok(DeadlockRetry.run(() -> orderService.createDirect(userId, dto)));
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

    /**
     * 快捷支付（等价于「用模拟渠道创建支付单并立刻支付成功」）。
     *
     * <p>保留这个接口是为了兼容既有调用方与自动化脚本；真实收银台走
     * {@code POST /portal/payment} + 渠道回调。两者最终都落到
     * {@code OrderService.markPaid}，所以库存/销量的处理完全一致。
     */
    @Operation(summary = "快捷支付（模拟渠道，适合本地与自动化测试）")
    @PostMapping("/{id}/pay")
    public R<Void> pay(@PathVariable Long id) {
        Long userId = UserContext.requireUserId();
        DeadlockRetry.run(() -> {
            paymentService.payWithMock(userId, id);
            return null;
        });
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
