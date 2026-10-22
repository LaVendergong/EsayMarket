package com.mall.core.pay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.exception.BusinessException;
import com.mall.common.result.ResultCode;
import com.mall.core.enums.PayChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 微信支付 Native（扫码支付）。
 *
 * <p>这也是**真实实现**：按 APIv3 规范拼 JSON、用商户私钥算
 * {@code WECHATPAY2-SHA256-RSA2048} 授权头、调 {@code /v3/pay/transactions/native} 拿 code_url；
 * 回调先验签（平台证书）再用 APIv3 密钥 AES-256-GCM 解密。
 *
 * <p>注意两点：
 * <ul>
 *   <li>平台证书不配时**只能解密不能验签** —— 生产环境必须配，否则任何人都能伪造"支付成功"回调。
 *       本地联调可以显式打开 {@code allow-unverified-notify}，但会打警告日志。</li>
 *   <li>金额单位是**分**，和库里的元要乘 100，别搞混。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WechatNativeChannel implements PaymentChannel {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PATH = "/v3/pay/transactions/native";
    /** 退款接口（申请退款） */
    private static final String PATH_REFUND = "/v3/refund/domestic/refunds";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private final PayProperties properties;

    @Override
    public PayChannel channel() {
        return PayChannel.WECHAT_NATIVE;
    }

    @Override
    public boolean available() {
        return properties.getWechat().configured();
    }

    @Override
    public PayModels.CreateResult create(PayModels.CreateCommand command) {
        PayProperties.Wechat cfg = properties.getWechat();
        if (!available()) {
            throw new BusinessException(ResultCode.PAY_CHANNEL_UNAVAILABLE, "微信支付渠道未配置（需要 appId / mchId / 证书序列号 / 商户私钥）");
        }
        long fen = command.getAmount().multiply(BigDecimal.valueOf(100)).setScale(0, java.math.RoundingMode.HALF_UP).longValue();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appid", cfg.getAppId());
        body.put("mchid", cfg.getMchId());
        body.put("description", command.getSubject());
        body.put("out_trade_no", command.getPaymentNo());
        body.put("notify_url", cfg.getNotifyUrl());
        Map<String, Object> amount = new LinkedHashMap<>();
        amount.put("total", fen);
        amount.put("currency", "CNY");
        body.put("amount", amount);

        String bodyJson;
        try {
            bodyJson = JSON.writeValueAsString(body);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.FAILED, "微信支付请求参数序列化失败");
        }

        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String nonce = randomNonce();
        String authorization = buildAuthorization(cfg, "POST", PATH, bodyJson, timestamp, nonce);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(cfg.getApiBase() + PATH))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", authorization)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> resp = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                log.warn("[pay] wechat native failed status={} body={}", resp.statusCode(), resp.body());
                throw new BusinessException(ResultCode.PAY_CHANNEL_UNAVAILABLE, "微信下单失败：" + resp.statusCode());
            }
            String codeUrl = JSON.readTree(resp.body()).path("code_url").asText(null);
            if (codeUrl == null || codeUrl.isBlank()) {
                throw new BusinessException(ResultCode.PAY_CHANNEL_UNAVAILABLE, "微信下单未返回 code_url");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("codeUrl", codeUrl);
            out.put("paymentNo", command.getPaymentNo());
            log.info("[pay] wechat native order created paymentNo={} amount={}", command.getPaymentNo(), command.getAmount());
            return PayModels.CreateResult.builder().payParams(out).build();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[pay] wechat native call error: {}", e.toString());
            throw new BusinessException(ResultCode.PAY_CHANNEL_UNAVAILABLE, "微信支付下单请求失败");
        }
    }

    /**
     * 原路退款（{@code POST /v3/refund/domestic/refunds}）。
     *
     * <p>{@code out_refund_no} 传我们的退款单号，微信按它幂等。
     * 返回 {@code PROCESSING} 表示"已受理、还没到账"，这种情况记成「退款中」而不是「退款成功」——
     * 告诉用户"钱已到账"但实际没到，是最容易招投诉的错误。
     *
     * <p>已知限制：响应没有验签（需要平台证书），理由同支付宝渠道。
     */
    @Override
    public PayModels.RefundResult refund(PayModels.RefundCommand command) {
        PayProperties.Wechat cfg = properties.getWechat();
        if (!available()) {
            throw new BusinessException(ResultCode.PAY_CHANNEL_UNAVAILABLE, "微信支付渠道未配置");
        }
        long refundFen = toFen(command.getAmount());
        long totalFen = toFen(command.getTotalAmount());
        if (refundFen <= 0) {
            throw new BusinessException(ResultCode.REFUND_FAILED, "退款金额必须大于 0");
        }
        if (totalFen > 0 && refundFen > totalFen) {
            throw new BusinessException(ResultCode.REFUND_FAILED, "退款金额不能大于原支付金额");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("out_trade_no", command.getPaymentNo());
        body.put("out_refund_no", command.getRefundNo());
        if (command.getReason() != null && !command.getReason().isBlank()) {
            body.put("reason", command.getReason());
        }
        Map<String, Object> amount = new LinkedHashMap<>();
        amount.put("refund", refundFen);
        amount.put("total", totalFen);
        amount.put("currency", "CNY");
        body.put("amount", amount);

        String bodyJson;
        try {
            bodyJson = JSON.writeValueAsString(body);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.FAILED, "退款请求参数序列化失败");
        }

        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String nonce = randomNonce();
        String authorization = buildAuthorization(cfg, "POST", PATH_REFUND, bodyJson, timestamp, nonce);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(cfg.getApiBase() + PATH_REFUND))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", authorization)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> resp = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String respBody = resp.body();
            if (resp.statusCode() != 200) {
                log.warn("[pay] wechat refund rejected status={} body={}", resp.statusCode(), respBody);
                return PayModels.RefundResult.builder().success(false).raw(respBody).build();
            }
            var node = JSON.readTree(respBody);
            String status = node.path("status").asText("");
            boolean ok = "SUCCESS".equals(status) || "PROCESSING".equals(status);
            return PayModels.RefundResult.builder()
                    .success(ok)
                    .pending("PROCESSING".equals(status))
                    .channelRefundNo(node.path("refund_id").asText(null))
                    .raw(respBody)
                    .build();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[pay] wechat refund call error: {}", e.toString());
            throw new BusinessException(ResultCode.REFUND_FAILED, "微信退款请求失败");
        }
    }

    /**
     * 主动查退款单（{@code GET /v3/refund/domestic/refunds/{out_refund_no}}）。
     *
     * <p>这是「退款中」能自动收敛的关键：退款通知会丢，但状态可以主动问。
     */
    @Override
    public PayModels.RefundNotifyResult queryRefund(PayModels.RefundQuery query) {
        PayProperties.Wechat cfg = properties.getWechat();
        if (!available()) {
            throw new BusinessException(ResultCode.PAY_CHANNEL_UNAVAILABLE, "微信支付渠道未配置");
        }
        String path = PATH_REFUND + "/" + query.getRefundNo();
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String nonce = randomNonce();
        // GET 没有请求体，待签名串里 body 位置是空字符串（末尾换行仍不能少）
        String authorization = buildAuthorization(cfg, "GET", path, "", timestamp, nonce);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(cfg.getApiBase() + path))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", authorization)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> resp = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                throw new BusinessException(ResultCode.REFUND_FAILED, "微信退款查询失败：" + resp.statusCode());
            }
            var node = JSON.readTree(resp.body());
            return PayModels.RefundNotifyResult.builder()
                    .refundNo(query.getRefundNo())
                    .channelRefundNo(node.path("refund_id").asText(null))
                    .status(node.path("status").asText("PROCESSING"))
                    .raw(resp.body())
                    .build();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[pay] wechat refund query error: {}", e.toString());
            throw new BusinessException(ResultCode.REFUND_FAILED, "微信退款查询请求失败");
        }
    }

    /**
     * 退款异步通知（{@code REFUND.SUCCESS} / {@code REFUND.ABNORMAL} / {@code REFUND.CLOSED}）。
     *
     * <p>注意退款通知里的字段是 {@code refund_status}，**不是**支付通知里的 {@code trade_state}；
     * 用错字段会永远判不出成功。
     */
    @Override
    public PayModels.RefundNotifyResult parseRefundNotify(Map<String, String> params, String body,
                                                          Map<String, String> headers) {
        PayProperties.Wechat cfg = properties.getWechat();
        if (!available()) {
            throw new BusinessException(ResultCode.PAY_CHANNEL_UNAVAILABLE, "微信支付渠道未配置");
        }
        verifySignature(cfg, headers, body);
        try {
            JsonNode resource = JSON.readTree(body).path("resource");
            String plain = decryptGcm(cfg.getApiV3Key(), resource.path("associated_data").asText(""),
                    resource.path("nonce").asText(), resource.path("ciphertext").asText());
            JsonNode data = JSON.readTree(plain);
            return PayModels.RefundNotifyResult.builder()
                    .refundNo(data.path("out_refund_no").asText())
                    .channelRefundNo(data.path("refund_id").asText(null))
                    .status(data.path("refund_status").asText(""))
                    .raw(body)
                    .build();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ResultCode.PAY_NOTIFY_INVALID, "微信退款通知解析失败");
        }
    }

    private static long toFen(BigDecimal yuan) {
        return yuan == null ? 0L
                : yuan.multiply(BigDecimal.valueOf(100)).setScale(0, java.math.RoundingMode.HALF_UP).longValue();
    }

    @Override
    public PayModels.NotifyResult parseNotify(Map<String, String> params, String body, Map<String, String> headers) {
        PayProperties.Wechat cfg = properties.getWechat();
        if (!available()) {
            throw new BusinessException(ResultCode.PAY_CHANNEL_UNAVAILABLE, "微信支付渠道未配置");
        }
        verifySignature(cfg, headers, body);

        try {
            JsonNode root = JSON.readTree(body);
            JsonNode resource = root.path("resource");
            String plain = decryptGcm(cfg.getApiV3Key(), resource.path("associated_data").asText(""),
                    resource.path("nonce").asText(), resource.path("ciphertext").asText());
            JsonNode data = JSON.readTree(plain);
            boolean paid = "SUCCESS".equals(data.path("trade_state").asText());
            JsonNode total = data.path("amount").path("total");
            BigDecimal amount = total.isMissingNode() ? null
                    : BigDecimal.valueOf(total.asLong()).divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
            return PayModels.NotifyResult.builder()
                    .paymentNo(data.path("out_trade_no").asText())
                    .channelTradeNo(data.path("transaction_id").asText())
                    .paid(paid)
                    .amount(amount)
                    .raw(body)
                    .build();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ResultCode.PAY_NOTIFY_INVALID, "微信回调报文解析失败");
        }
    }

    /**
     * 构造 WECHATPAY2-SHA256-RSA2048 授权头（抽出来是为了能单测 —— 不联网也能验签）。
     *
     * <p>待签名串严格按微信规范：{@code 方法\nURL\ntimestamp\nnonce\n报文\n}，
     * URL 是 path（含 query），最后那个换行不能少。
     */
    static String buildAuthorization(PayProperties.Wechat cfg, String method, String path,
                                     String body, String timestamp, String nonce) {
        String message = method + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + body + "\n";
        String signature = RsaSigner.sign(message, cfg.getPrivateKey());
        return "WECHATPAY2-SHA256-RSA2048 "
                + "mchid=\"" + cfg.getMchId() + "\","
                + "nonce_str=\"" + nonce + "\","
                + "signature=\"" + signature + "\","
                + "timestamp=\"" + timestamp + "\","
                + "serial_no=\"" + cfg.getSerialNo() + "\"";
    }

    /** 回调验签：平台证书不配时，只有在显式允许的情况下才跳过（并打警告） */
    private void verifySignature(PayProperties.Wechat cfg, Map<String, String> headers, String body) {
        String cert = cfg.getPlatformCert();
        if (cert == null || cert.isBlank()) {
            if (!cfg.isAllowUnverifiedNotify()) {
                throw new BusinessException(ResultCode.PAY_NOTIFY_INVALID,
                        "微信平台证书未配置，拒绝处理回调（生产必须配置证书；本地联调可显式开启 allow-unverified-notify）");
            }
            log.warn("[pay] wechat notify accepted WITHOUT signature verification (dev only!)");
            return;
        }
        String timestamp = header(headers, "Wechatpay-Timestamp");
        String nonce = header(headers, "Wechatpay-Nonce");
        String signature = header(headers, "Wechatpay-Signature");
        if (timestamp == null || nonce == null || signature == null) {
            throw new BusinessException(ResultCode.PAY_NOTIFY_INVALID, "微信回调缺少签名头");
        }
        String message = timestamp + "\n" + nonce + "\n" + body + "\n";
        if (!RsaSigner.verify(message, signature, cert)) {
            throw new BusinessException(ResultCode.PAY_NOTIFY_INVALID, "微信回调验签失败");
        }
    }

    /** APIv3 回调报文用 AES-256-GCM 加密，密钥就是 APIv3 密钥 */
    static String decryptGcm(String apiV3Key, String associatedData, String nonce, String ciphertext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKeySpec key = new SecretKeySpec(apiV3Key.getBytes(StandardCharsets.UTF_8), "AES");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
        if (associatedData != null && !associatedData.isEmpty()) {
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
        }
        byte[] plain = cipher.doFinal(java.util.Base64.getDecoder().decode(ciphertext));
        return new String(plain, StandardCharsets.UTF_8);
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null) {
            return null;
        }
        return headers.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst().orElse(null);
    }

    private static String randomNonce() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder(32);
        for (byte x : b) {
            sb.append(String.format("%02X", x));
        }
        return sb.toString();
    }
}
