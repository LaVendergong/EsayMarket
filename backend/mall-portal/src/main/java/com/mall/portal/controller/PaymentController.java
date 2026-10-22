package com.mall.portal.controller;

import com.mall.common.result.R;
import com.mall.core.model.dto.PaymentCreateDTO;
import com.mall.core.model.vo.PaymentVO;
import com.mall.core.security.UserContext;
import com.mall.core.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "支付")
@RestController
@RequestMapping("/api/v1/portal")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @Operation(summary = "可用支付渠道")
    @GetMapping("/payment/channels")
    public R<List<Map<String, String>>> channels() {
        return R.ok(paymentService.availableChannels());
    }

    @Operation(summary = "为订单创建支付单（返回调起参数）")
    @PostMapping("/payment")
    public R<PaymentVO> create(@Valid @RequestBody PaymentCreateDTO dto) {
        return R.ok(paymentService.create(UserContext.requireUserId(), dto.getOrderId(), dto.getChannel()));
    }

    @Operation(summary = "查询支付单（前端轮询）")
    @GetMapping("/payment/{paymentNo}")
    public R<PaymentVO> detail(@PathVariable String paymentNo) {
        return R.ok(paymentService.detail(UserContext.requireUserId(), paymentNo));
    }

    @Operation(summary = "模拟支付成功（仅 MOCK 渠道，dev/测试用）")
    @PostMapping("/payment/{paymentNo}/mock-pay")
    public R<Void> mockPay(@PathVariable String paymentNo) {
        paymentService.mockPay(UserContext.requireUserId(), paymentNo);
        return R.ok(null);
    }

    @Operation(summary = "关闭支付单")
    @PostMapping("/payment/{paymentNo}/close")
    public R<Void> close(@PathVariable String paymentNo) {
        paymentService.close(UserContext.requireUserId(), paymentNo);
        return R.ok(null);
    }

    /**
     * 退款结果异步通知（微信 REFUND.SUCCESS / ABNORMAL / CLOSED）。
     *
     * <p>和支付回调一样必须匿名可达，安全性靠验签 + 报文解密。
     * 支付宝的退款是同步接口、没有独立退款通知，所以走主动查单（后台/定时任务）。
     */
    @Operation(summary = "退款渠道异步回调")
    @PostMapping("/pay/refund-notify/{channel}")
    public String refundNotify(@PathVariable String channel,
                               HttpServletRequest request,
                               @RequestBody(required = false) String body) {
        Map<String, String> params = new HashMap<>();
        request.getParameterMap().forEach((k, v) -> params.put(k, v.length > 0 ? v[0] : ""));
        Map<String, String> headers = new HashMap<>();
        request.getHeaderNames().asIterator().forEachRemaining(h -> headers.put(h, request.getHeader(h)));

        paymentService.handleRefundNotify(channel, params, body == null ? "" : body, headers);
        return "{\"code\":\"SUCCESS\",\"message\":\"OK\"}";
    }

    /**
     * 支付渠道异步回调。
     *
     * <p>这个路径**必须放行匿名访问**（渠道服务器不会带我们的 token），安全性完全依赖
     * 各渠道的验签 —— 验不过就抛异常，不会走到改订单状态。
     */
    @Operation(summary = "支付渠道异步回调")
    @PostMapping("/pay/notify/{channel}")
    public String notify(@PathVariable String channel,
                         HttpServletRequest request,
                         @RequestBody(required = false) String body) {
        Map<String, String> params = new HashMap<>();
        request.getParameterMap().forEach((k, v) -> params.put(k, v.length > 0 ? v[0] : ""));
        Map<String, String> headers = new HashMap<>();
        request.getHeaderNames().asIterator().forEachRemaining(h -> headers.put(h, request.getHeader(h)));

        paymentService.handleNotify(channel, params, body == null ? "" : body, headers);

        // 支付宝要求回 "success"（纯文本），微信要求 {"code":"SUCCESS"}；两者都认，返回 JSON 兼容
        return "{\"code\":\"SUCCESS\",\"message\":\"OK\"}";
    }
}
