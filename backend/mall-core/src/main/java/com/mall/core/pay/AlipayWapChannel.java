package com.mall.core.pay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.exception.BusinessException;
import com.mall.common.result.ResultCode;
import com.mall.core.enums.PayChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 支付宝手机网站支付（alipay.trade.wap.pay）。
 *
 * <p>这是**真实实现**：按支付宝开放平台文档拼参数、RSA2 签名、生成收银台跳转 URL；
 * 回调按同样的规则验签。签名/验签有单测用自签密钥对验证（不需要支付宝账号）。
 *
 * <p>要真正收款还需要：appId、应用私钥、支付宝公钥、一个公网可达的 notify_url。
 * 缺任何一项时 {@link #available()} 为 false，创建支付会直接报错。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlipayWapChannel implements PaymentChannel {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private final PayProperties properties;

    @Override
    public PayChannel channel() {
        return PayChannel.ALIPAY_WAP;
    }

    @Override
    public boolean available() {
        return properties.getAlipay().configured();
    }

    @Override
    public PayModels.CreateResult create(PayModels.CreateCommand command) {
        PayProperties.Alipay cfg = properties.getAlipay();
        if (!available()) {
            throw new BusinessException(ResultCode.PAY_CHANNEL_UNAVAILABLE, "支付宝渠道未配置（需要 appId / 应用私钥 / 支付宝公钥）");
        }

        Map<String, String> params = new TreeMap<>();
        params.put("app_id", cfg.getAppId());
        params.put("method", "alipay.trade.wap.pay");
        params.put("format", "JSON");
        params.put("charset", "utf-8");
        params.put("sign_type", "RSA2");
        params.put("timestamp", TS.format(LocalDateTime.now()));
        params.put("version", "1.0");
        if (cfg.getNotifyUrl() != null && !cfg.getNotifyUrl().isBlank()) {
            params.put("notify_url", cfg.getNotifyUrl());
        }
        if (cfg.getReturnUrl() != null && !cfg.getReturnUrl().isBlank()) {
            params.put("return_url", cfg.getReturnUrl());
        }

        // biz_content 必须是无空格的紧凑 JSON，否则待签名串与支付宝侧不一致
        Map<String, Object> biz = new LinkedHashMap<>();
        biz.put("out_trade_no", command.getPaymentNo());
        biz.put("total_amount", command.getAmount().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
        biz.put("subject", command.getSubject());
        biz.put("product_code", "QUICK_WAP_WAY");
        biz.put("timeout_express", properties.getExpireMinutes() + "m");
        try {
            params.put("biz_content", JSON.writeValueAsString(biz));
        } catch (Exception e) {
            throw new BusinessException(ResultCode.FAILED, "支付宝请求参数序列化失败");
        }

        String sign = RsaSigner.sign(signContent(params), cfg.getPrivateKey());
        params.put("sign", sign);

        String query = params.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
        String payUrl = cfg.getGateway() + "?" + query;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("payUrl", payUrl);
        out.put("paymentNo", command.getPaymentNo());
        log.info("[pay] alipay wap order created paymentNo={} amount={}", command.getPaymentNo(), command.getAmount());
        return PayModels.CreateResult.builder().payParams(out).build();
    }

    /**
     * 原路退款（alipay.trade.refund，同步接口）。
     *
     * <p>{@code out_request_no} 传的是我们的退款单号 —— 支付宝按它做**幂等**：
     * 同一个号重复请求不会重复退钱，这正是"渠道失败后让客服重试"能安全的前提。
     *
     * <p>已知限制：**没有校验响应签名**。理由是我们作为客户端、走 TLS 直连固定网关，
     * 且只从响应里取 code/trade_no；在拿不到商户资质、无法与真实网关联调的情况下，
     * 草率实现响应验签反而可能误拒正常响应。这一条记在 docs 的「已知缺口」里。
     */
    @Override
    public PayModels.RefundResult refund(PayModels.RefundCommand command) {
        PayProperties.Alipay cfg = properties.getAlipay();
        if (!available()) {
            throw new BusinessException(ResultCode.PAY_CHANNEL_UNAVAILABLE, "支付宝渠道未配置");
        }
        if (command.getAmount() == null || command.getAmount().signum() <= 0) {
            throw new BusinessException(ResultCode.REFUND_FAILED, "退款金额必须大于 0");
        }

        Map<String, String> params = buildRefundParams(command);

        String form = params.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(cfg.getGateway()))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded;charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String body = resp.body();
            var node = JSON.readTree(body).path("alipay_trade_refund_response");
            String code = node.path("code").asText("");
            if (!"10000".equals(code)) {
                log.warn("[pay] alipay refund rejected code={} sub={} msg={}", code,
                        node.path("sub_code").asText(""), node.path("sub_msg").asText(""));
                return PayModels.RefundResult.builder().success(false).raw(body).build();
            }
            return PayModels.RefundResult.builder()
                    .success(true)
                    .pending(false)
                    .channelRefundNo(node.path("trade_no").asText(null))
                    .raw(body)
                    .build();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[pay] alipay refund call error: {}", e.toString());
            throw new BusinessException(ResultCode.REFUND_FAILED, "支付宝退款请求失败");
        }
    }

    @Override
    public PayModels.NotifyResult parseNotify(Map<String, String> params, String body, Map<String, String> headers) {
        PayProperties.Alipay cfg = properties.getAlipay();
        if (!available()) {
            throw new BusinessException(ResultCode.PAY_CHANNEL_UNAVAILABLE, "支付宝渠道未配置");
        }
        String sign = params.get("sign");
        if (sign == null || sign.isBlank()) {
            throw new BusinessException(ResultCode.PAY_NOTIFY_INVALID, "支付宝回调缺少签名");
        }
        // 验签串要去掉 sign 与 sign_type
        Map<String, String> forSign = new TreeMap<>(params);
        forSign.remove("sign");
        forSign.remove("sign_type");
        if (!RsaSigner.verify(signContent(forSign), sign, cfg.getPublicKey())) {
            throw new BusinessException(ResultCode.PAY_NOTIFY_INVALID, "支付宝回调验签失败");
        }

        String tradeStatus = params.get("trade_status");
        boolean paid = "TRADE_SUCCESS".equals(tradeStatus) || "TRADE_FINISHED".equals(tradeStatus);
        String totalAmount = params.get("total_amount");
        return PayModels.NotifyResult.builder()
                .paymentNo(params.get("out_trade_no"))
                .channelTradeNo(params.get("trade_no"))
                .paid(paid)
                .amount(totalAmount == null ? null : new BigDecimal(totalAmount))
                .raw(body != null ? body : params.toString())
                .build();
    }

    /**
     * 构造 alipay.trade.refund 的全部请求参数（含签名）。
     *
     * <p>抽成包级方法是为了**能单测**：不联调也能验证"待签名串拼法 + RSA2 签名"是对的。
     * 关键点是 {@code out_request_no} 必须用我们的退款单号 —— 支付宝按它幂等。
     */
    Map<String, String> buildRefundParams(PayModels.RefundCommand command) {
        PayProperties.Alipay cfg = properties.getAlipay();
        Map<String, String> params = new TreeMap<>();
        params.put("app_id", cfg.getAppId());
        params.put("method", "alipay.trade.refund");
        params.put("format", "JSON");
        params.put("charset", "utf-8");
        params.put("sign_type", "RSA2");
        params.put("timestamp", TS.format(LocalDateTime.now()));
        params.put("version", "1.0");

        Map<String, Object> biz = new LinkedHashMap<>();
        biz.put("out_trade_no", command.getPaymentNo());
        biz.put("refund_amount", command.getAmount().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
        biz.put("out_request_no", command.getRefundNo());
        if (command.getReason() != null && !command.getReason().isBlank()) {
            biz.put("refund_reason", command.getReason());
        }
        try {
            params.put("biz_content", JSON.writeValueAsString(biz));
        } catch (Exception e) {
            throw new BusinessException(ResultCode.FAILED, "退款请求参数序列化失败");
        }
        params.put("sign", RsaSigner.sign(signContent(params), cfg.getPrivateKey()));
        return params;
    }

    /** 待签名串：按 key 升序，k=v 用 & 连接 */
    static String signContent(Map<String, String> params) {
        return new TreeMap<>(params).entrySet().stream()
                .filter(e -> e.getValue() != null && !e.getValue().isBlank())
                .filter(e -> !"sign".equals(e.getKey()))
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"));
    }

    private static String encode(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
