package com.mall.admin.controller;

import com.mall.common.result.R;
import com.mall.core.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "后台支付")
@RestController
@RequestMapping("/api/v1/admin/payment")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * 主动同步一笔支付的真实状态（对账兜底）。
     *
     * <p>典型场景：用户说"我付过钱了"，但订单还是待支付 —— 回调可能丢了。
     * 客服点这个接口，会去渠道查一次真实状态，确认收款就补记已支付。
     */
    @Operation(summary = "主动同步支付状态（回调丢失兜底）")
    @PostMapping("/{paymentNo}/sync")
    public R<Map<String, Object>> sync(@PathVariable String paymentNo) {
        boolean paid = paymentService.syncPayment(paymentNo);
        return R.ok(Map.of("paymentNo", paymentNo, "paid", paid));
    }
}
