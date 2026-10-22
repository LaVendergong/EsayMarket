package com.mall.core.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.exception.BusinessException;
import com.mall.common.result.ResultCode;
import com.mall.core.entity.Order;
import com.mall.core.entity.Payment;
import com.mall.core.enums.OrderStatus;
import com.mall.core.enums.PayChannel;
import com.mall.core.enums.PaymentStatus;
import com.mall.core.mapper.OrderMapper;
import com.mall.core.mapper.PaymentMapper;
import com.mall.core.pay.PayModels;
import com.mall.core.pay.PayProperties;
import com.mall.core.pay.PaymentChannel;
import com.mall.core.model.vo.PaymentVO;
import com.mall.core.service.OrderService;
import com.mall.core.service.PaymentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 支付编排。
 *
 * <p>职责边界：**这笔钱到底收没收到由渠道说了算**，这里只负责「下单 → 等回调 → 幂等置为已支付 →
 * 通知订单模块」这条链路。所以三个不变式都落在这里：
 *
 * <ol>
 *   <li>只有「待支付」的支付单能变成「已支付」（条件 UPDATE + 影响行数），重复回调不会重复扣库存</li>
 *   <li>回调里的金额必须等于支付单金额（{@link ResultCode#PAY_AMOUNT_MISMATCH}），防篡改</li>
 *   <li>一笔订单同时只允许存在一个待支付单 —— 换渠道/重新发起时先关掉旧的，避免"两笔都能付成功"</li>
 * </ol>
 */
@Slf4j
@Service
public class PaymentServiceImpl implements PaymentService {

    private static final DateTimeFormatter NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PaymentMapper paymentMapper;
    private final OrderMapper orderMapper;
    private final OrderService orderService;
    private final PayProperties payProperties;
    /** code -> 渠道实现，Spring 自动注入全部实现 */
    private final Map<String, PaymentChannel> channels;

    public PaymentServiceImpl(PaymentMapper paymentMapper,
                              OrderMapper orderMapper,
                              OrderService orderService,
                              PayProperties payProperties,
                              List<PaymentChannel> channelList) {
        this.paymentMapper = paymentMapper;
        this.orderMapper = orderMapper;
        this.orderService = orderService;
        this.payProperties = payProperties;
        this.channels = channelList.stream()
                .collect(Collectors.toMap(c -> c.channel().getCode(), Function.identity()));
    }

    @Override
    public List<Map<String, String>> availableChannels() {
        List<Map<String, String>> out = new ArrayList<>();
        for (PayChannel c : PayChannel.values()) {
            PaymentChannel impl = channels.get(c.getCode());
            if (impl != null && impl.available()) {
                out.add(Map.of("code", c.getCode(), "text", c.getText()));
            }
        }
        return out;
    }

    @Override
    @Transactional
    public PaymentVO create(Long userId, Long orderId, String channelCode) {
        PayChannel payChannel = PayChannel.of(channelCode);
        if (payChannel == null) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "支付渠道不正确");
        }
        PaymentChannel impl = channels.get(payChannel.getCode());
        if (impl == null || !impl.available()) {
            throw new BusinessException(ResultCode.PAY_CHANNEL_UNAVAILABLE,
                    payChannel.getText() + " 未配置，请换一种支付方式");
        }

        Order order = orderMapper.selectById(orderId);
        if (order == null || !Objects.equals(order.getUserId(), userId)) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        if (order.getStatus() == null || order.getStatus() != OrderStatus.UNPAID.getCode()) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ERROR,
                    "当前订单状态（" + OrderStatus.textOf(order.getStatus()) + "）不需要支付");
        }
        if (order.getExpireTime() != null && !order.getExpireTime().isAfter(LocalDateTime.now())) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ERROR, "订单已超时，无法支付");
        }

        // 先关掉这一单之前没付掉的支付单，避免同时存在两笔可支付的单（用户换渠道时最容易踩）
        closePending(orderId, null);

        Payment p = new Payment();
        p.setPaymentNo(newPaymentNo());
        p.setOrderId(order.getId());
        p.setOrderNo(order.getOrderNo());
        p.setUserId(userId);
        p.setChannel(payChannel.getCode());
        p.setAmount(order.getPayAmount());
        p.setStatus(PaymentStatus.PENDING.getCode());
        p.setExpireTime(LocalDateTime.now().plusMinutes(payProperties.getExpireMinutes()));
        paymentMapper.insert(p);

        PayModels.CreateResult result = impl.create(PayModels.CreateCommand.builder()
                .paymentNo(p.getPaymentNo())
                .orderNo(order.getOrderNo())
                .amount(order.getPayAmount())
                .subject("Mall 商城订单 " + order.getOrderNo())
                .build());

        Payment patch = new Payment();
        patch.setId(p.getId());
        patch.setPayParams(toJson(result.getPayParams()));
        patch.setChannelTradeNo(result.getChannelTradeNo());
        paymentMapper.updateById(patch);

        log.info("[pay] created paymentNo={} orderNo={} channel={} amount={}",
                p.getPaymentNo(), p.getOrderNo(), p.getChannel(), p.getAmount());
        return PaymentVO.from(p, result.getPayParams());
    }

    @Override
    public PaymentVO detail(Long userId, String paymentNo) {
        Payment p = requireOwned(userId, paymentNo);
        return PaymentVO.from(p, parseParams(p.getPayParams()));
    }

    @Override
    @Transactional
    public void mockPay(Long userId, String paymentNo) {
        Payment p = requireOwned(userId, paymentNo);
        if (!PayChannel.MOCK.getCode().equals(p.getChannel())) {
            throw new BusinessException(ResultCode.PAYMENT_STATUS_ERROR, "只有模拟支付渠道才能这样支付");
        }
        markPaid(p, "MOCK-" + p.getPaymentNo(), p.getAmount(), "{\"mock\":true}");
    }

    @Override
    @Transactional
    public void close(Long userId, String paymentNo) {
        Payment p = requireOwned(userId, paymentNo);
        int n = paymentMapper.update(closedPatch(), Wrappers.<Payment>lambdaUpdate()
                .eq(Payment::getId, p.getId())
                .eq(Payment::getStatus, PaymentStatus.PENDING.getCode()));
        if (n == 0) {
            throw new BusinessException(ResultCode.PAYMENT_STATUS_ERROR, "只有待支付的支付单才能关闭");
        }
    }

    @Override
    @Transactional
    public void handleNotify(String channelCode, Map<String, String> params, String body, Map<String, String> headers) {
        PayChannel payChannel = PayChannel.of(channelCode);
        PaymentChannel impl = payChannel == null ? null : channels.get(payChannel.getCode());
        if (impl == null) {
            throw new BusinessException(ResultCode.PAY_CHANNEL_UNAVAILABLE, "未知的支付渠道");
        }
        // 验签失败会在这里抛异常，绝不允许"验证不过就当成失败通知"继续走
        PayModels.NotifyResult r = impl.parseNotify(params, body, headers);
        if (r.getPaymentNo() == null || r.getPaymentNo().isBlank()) {
            throw new BusinessException(ResultCode.PAY_NOTIFY_INVALID, "回调缺少商户订单号");
        }
        Payment p = paymentMapper.selectOne(Wrappers.<Payment>lambdaQuery()
                .eq(Payment::getPaymentNo, r.getPaymentNo()));
        if (p == null) {
            throw new BusinessException(ResultCode.PAYMENT_NOT_FOUND, "回调对应的支付单不存在");
        }
        if (!Objects.equals(p.getChannel(), impl.channel().getCode())) {
            throw new BusinessException(ResultCode.PAY_NOTIFY_INVALID, "回调渠道与支付单不一致");
        }
        if (!r.isPaid()) {
            // 渠道明确说没成功：记下原文，状态交给下次回调/用户重试，不当成失败处理
            recordNotify(p.getId(), r.getRaw());
            log.info("[pay] notify received but not paid paymentNo={}", p.getPaymentNo());
            return;
        }
        markPaid(p, r.getChannelTradeNo(), r.getAmount(), r.getRaw());
    }

    @Override
    @Transactional
    public PaymentVO payWithMock(Long userId, Long orderId) {
        PaymentVO vo = create(userId, orderId, PayChannel.MOCK.getCode());
        Payment p = paymentMapper.selectOne(Wrappers.<Payment>lambdaQuery()
                .eq(Payment::getPaymentNo, vo.getPaymentNo()));
        markPaid(p, "MOCK-" + p.getPaymentNo(), p.getAmount(), "{\"mock\":true}");
        return PaymentVO.from(paymentMapper.selectById(p.getId()), vo.getPayParams());
    }

    // ================================================================ 内部

    /**
     * 幂等地把支付单置为已支付，并通知订单。
     *
     * <p>先做金额校验再改状态：金额不对直接抛错，**不能**先把状态改了再报错。
     * 然后靠「status=PENDING 的条件 UPDATE」保证并发/重复回调只有一个能成功。
     */
    private void markPaid(Payment p, String channelTradeNo, BigDecimal payAmount, String raw) {
        if (payAmount != null && p.getAmount() != null && p.getAmount().compareTo(payAmount) != 0) {
            throw new BusinessException(ResultCode.PAY_AMOUNT_MISMATCH,
                    "回调金额 " + payAmount + " 与支付单金额 " + p.getAmount() + " 不一致");
        }
        Payment patch = new Payment();
        patch.setStatus(PaymentStatus.PAID.getCode());
        patch.setPaidTime(LocalDateTime.now());
        patch.setChannelTradeNo(channelTradeNo);
        patch.setNotifyTime(LocalDateTime.now());
        patch.setNotifyRaw(truncate(raw));
        int n = paymentMapper.update(patch, Wrappers.<Payment>lambdaUpdate()
                .eq(Payment::getId, p.getId())
                .eq(Payment::getStatus, PaymentStatus.PENDING.getCode()));
        if (n == 0) {
            // 影响 0 行有两种完全不同的情况，**不能一律当幂等放过**：
            //   1) 已经是 PAID —— 渠道重复回调/用户连点，幂等返回，不能重复扣库存
            //   2) CLOSED / FAILED —— 这笔支付单已经作废，必须报错
            // 早先这里只有 case 1，导致「对已关闭的支付单调用 mock-pay」返回 200，
            // 接口在说谎（订单没变，但调用方以为付成功了）。
            Payment current = paymentMapper.selectById(p.getId());
            if (current != null && Objects.equals(current.getStatus(), PaymentStatus.PAID.getCode())) {
                log.info("[pay] duplicate notify ignored paymentNo={}", p.getPaymentNo());
                return;
            }
            throw new BusinessException(ResultCode.PAYMENT_STATUS_ERROR,
                    "支付单状态为「" + PaymentStatus.textOf(current == null ? null : current.getStatus()) + "」，不能支付");
        }
        // 订单侧再兜一次幂等：markPaid 内部用条件 UPDATE，重复调用不会重复扣库存
        orderService.markPaid(p.getOrderId(), p.getChannel(), p.getPaymentNo());
    }

    private void closePending(Long orderId, Long exceptId) {
        Payment patch = closedPatch();
        var w = Wrappers.<Payment>lambdaUpdate()
                .eq(Payment::getOrderId, orderId)
                .eq(Payment::getStatus, PaymentStatus.PENDING.getCode());
        if (exceptId != null) {
            w.ne(Payment::getId, exceptId);
        }
        paymentMapper.update(patch, w);
    }

    private static Payment closedPatch() {
        Payment patch = new Payment();
        patch.setStatus(PaymentStatus.CLOSED.getCode());
        return patch;
    }

    private void recordNotify(Long paymentId, String raw) {
        Payment patch = new Payment();
        patch.setId(paymentId);
        patch.setNotifyTime(LocalDateTime.now());
        patch.setNotifyRaw(truncate(raw));
        paymentMapper.updateById(patch);
    }

    private Payment requireOwned(Long userId, String paymentNo) {
        Payment p = paymentMapper.selectOne(Wrappers.<Payment>lambdaQuery()
                .eq(Payment::getPaymentNo, paymentNo));
        if (p == null || !Objects.equals(p.getUserId(), userId)) {
            throw new BusinessException(ResultCode.PAYMENT_NOT_FOUND);
        }
        return p;
    }

    private static String newPaymentNo() {
        return "P" + NO_FORMAT.format(LocalDateTime.now())
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1000000));
    }

    private static String toJson(Map<String, Object> map) {
        try {
            return map == null ? null : JSON.writeValueAsString(map);
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> parseParams(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 2000 ? s.substring(0, 2000) : s;
    }
}
