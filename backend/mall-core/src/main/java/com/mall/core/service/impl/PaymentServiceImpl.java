package com.mall.core.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.exception.BusinessException;
import com.mall.common.result.ResultCode;
import com.mall.core.entity.AfterSale;
import com.mall.core.entity.Order;
import com.mall.core.entity.Payment;
import com.mall.core.enums.OrderStatus;
import com.mall.core.enums.PayChannel;
import com.mall.core.enums.PaymentStatus;
import com.mall.core.enums.RefundStatus;
import com.mall.core.mapper.AfterSaleMapper;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
    private final AfterSaleMapper afterSaleMapper;
    /** 对账任务要"逐笔一个事务"，用模板显式控制边界（不能在同一个事务里 catch 后继续） */
    private final TransactionTemplate transactionTemplate;
    /** code -> 渠道实现，Spring 自动注入全部实现 */
    private final Map<String, PaymentChannel> channels;

    public PaymentServiceImpl(PaymentMapper paymentMapper,
                              OrderMapper orderMapper,
                              OrderService orderService,
                              PayProperties payProperties,
                              AfterSaleMapper afterSaleMapper,
                              PlatformTransactionManager transactionManager,
                              List<PaymentChannel> channelList) {
        this.paymentMapper = paymentMapper;
        this.orderMapper = orderMapper;
        this.orderService = orderService;
        this.payProperties = payProperties;
        this.afterSaleMapper = afterSaleMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
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
    public PayModels.RefundResult refund(Long orderId, String refundNo, BigDecimal amount, String reason) {
        // 退到"实际收钱的那笔"：按 id 倒序取最近一笔 PAID，避免拿到早期失败/关闭的单
        Payment p = paymentMapper.selectOne(Wrappers.<Payment>lambdaQuery()
                .eq(Payment::getOrderId, orderId)
                .eq(Payment::getStatus, PaymentStatus.PAID.getCode())
                .orderByDesc(Payment::getId)
                .last("LIMIT 1"));
        if (p == null) {
            throw new BusinessException(ResultCode.PAYMENT_NOT_FOUND, "该订单没有成功的支付记录，无法原路退款");
        }
        PaymentChannel impl = channels.get(p.getChannel());
        if (impl == null || !impl.available()) {
            throw new BusinessException(ResultCode.REFUND_NOT_SUPPORTED, "原支付渠道不可用，无法原路退款");
        }
        if (!impl.supportsRefund()) {
            throw new BusinessException(ResultCode.REFUND_NOT_SUPPORTED, "原支付渠道不支持自动退款，请线下处理");
        }

        PayModels.RefundResult result = impl.refund(PayModels.RefundCommand.builder()
                .paymentNo(p.getPaymentNo())
                .refundNo(refundNo)
                .amount(amount)
                .totalAmount(p.getAmount())
                .reason(reason)
                .build());
        result.setChannel(p.getChannel());
        log.info("[pay] refund orderNo={} paymentNo={} refundNo={} amount={} channel={} success={} pending={}",
                p.getOrderNo(), p.getPaymentNo(), refundNo, amount, p.getChannel(), result.isSuccess(), result.isPending());
        return result;
    }

    /**
     * 渠道退款结果通知。
     *
     * <p>只处理「退款中 -> 成功/异常」。已经是成功/失败的单直接忽略（重复通知）。
     */
    @Override
    @Transactional
    public void handleRefundNotify(String channelCode, Map<String, String> params, String body, Map<String, String> headers) {
        PayChannel payChannel = PayChannel.of(channelCode);
        PaymentChannel impl = payChannel == null ? null : channels.get(payChannel.getCode());
        if (impl == null) {
            throw new BusinessException(ResultCode.PAY_CHANNEL_UNAVAILABLE, "未知的支付渠道");
        }
        PayModels.RefundNotifyResult r = impl.parseRefundNotify(params, body, headers);
        if (r.getRefundNo() == null || r.getRefundNo().isBlank()) {
            throw new BusinessException(ResultCode.PAY_NOTIFY_INVALID, "退款通知缺少商户退款单号");
        }
        AfterSale as = afterSaleMapper.selectOne(Wrappers.<AfterSale>lambdaQuery()
                .eq(AfterSale::getRefundNo, r.getRefundNo())
                .last("LIMIT 1"));
        if (as == null) {
            throw new BusinessException(ResultCode.AFTER_SALE_NOT_FOUND, "退款通知对应的售后单不存在");
        }
        applyRefundResult(as, r);
    }

    @Override
    public int queryRefund(Long afterSaleId) {
        AfterSale as = afterSaleMapper.selectById(afterSaleId);
        if (as == null) {
            throw new BusinessException(ResultCode.AFTER_SALE_NOT_FOUND);
        }
        refreshRefund(as);
        AfterSale after = afterSaleMapper.selectById(afterSaleId);
        return after == null || after.getRefundStatus() == null ? 0 : after.getRefundStatus();
    }

    /**
     * 对账兜底：退款通知会丢（网络、回调地址配错、服务重启），只靠通知「退款中」会永远停住。
     *
     * <p>只捞「退款中且 5 分钟没动过」的单，避免刚发起就查、把正常处理中的单刷成异常。
     */
    @Override
    public int reconcileProcessingRefunds(int limit) {
        List<AfterSale> pending = afterSaleMapper.selectList(Wrappers.<AfterSale>lambdaQuery()
                .eq(AfterSale::getRefundStatus, RefundStatus.PROCESSING.getCode())
                .isNotNull(AfterSale::getRefundNo)
                .lt(AfterSale::getUpdateTime, LocalDateTime.now().minusMinutes(5))
                .orderByAsc(AfterSale::getId)
                .last("LIMIT " + Math.max(1, limit)));
        int changed = 0;
        for (AfterSale as : pending) {
            try {
                if (refreshRefund(as)) {
                    changed += 1;
                }
            } catch (Exception e) {
                // 单条失败不能中断整批（渠道临时不可用很常见）
                log.warn("[pay] refund reconcile failed no={} err={}", as.getAfterSaleNo(), e.toString());
            }
        }
        if (changed > 0) {
            log.info("[pay] refund reconcile updated {} of {} processing refunds", changed, pending.size());
        }
        return changed;
    }

    /** 查一次渠道并落库；返回状态是否发生了变化 */
    private boolean refreshRefund(AfterSale as) {
        if (as.getRefundNo() == null || as.getRefundNo().isBlank()) {
            throw new BusinessException(ResultCode.REFUND_NOT_SUPPORTED, "该售后单没有退款单号，无法查询");
        }
        Payment p = paymentMapper.selectOne(Wrappers.<Payment>lambdaQuery()
                .eq(Payment::getOrderId, as.getOrderId())
                .eq(Payment::getStatus, PaymentStatus.PAID.getCode())
                .orderByDesc(Payment::getId)
                .last("LIMIT 1"));
        String code = as.getRefundChannel() != null ? as.getRefundChannel() : (p == null ? null : p.getChannel());
        PaymentChannel impl = code == null ? null : channels.get(code);
        if (impl == null || !impl.available()) {
            throw new BusinessException(ResultCode.REFUND_NOT_SUPPORTED, "原支付渠道不可用，无法查询退款");
        }
        PayModels.RefundNotifyResult r = impl.queryRefund(PayModels.RefundQuery.builder()
                .paymentNo(p == null ? null : p.getPaymentNo())
                .refundNo(as.getRefundNo())
                .build());
        return applyRefundResult(as, r);
    }

    /**
     * 把渠道给的退款结果落到售后单上。
     *
     * <p>只更新「退款中」的单（条件 UPDATE）：并发通知 + 定时任务同时到达时只会有一次生效。
     */
    private boolean applyRefundResult(AfterSale as, PayModels.RefundNotifyResult r) {
        if (r.getRefundNo() != null && !r.getRefundNo().equals(as.getRefundNo())) {
            throw new BusinessException(ResultCode.PAY_NOTIFY_INVALID, "退款单号与售后单不匹配");
        }
        if (!r.isSuccess() && !r.isFailed()) {
            // PROCESSING：渠道还在处理，什么都不改
            return false;
        }
        AfterSale patch = new AfterSale();
        if (r.isSuccess()) {
            patch.setRefundStatus(RefundStatus.SUCCESS.getCode());
            patch.setRefundTime(LocalDateTime.now());
            patch.setRefundError(null);
        } else {
            // ABNORMAL / CLOSED：钱没退成，标成失败等人工介入（售后单仍是"已同意"，差的是钱）
            patch.setRefundStatus(RefundStatus.FAILED.getCode());
            patch.setRefundError("渠道退款异常（" + r.getStatus() + "），需人工处理");
        }
        int n = afterSaleMapper.update(patch, Wrappers.<AfterSale>lambdaUpdate()
                .eq(AfterSale::getId, as.getId())
                .eq(AfterSale::getRefundStatus, RefundStatus.PROCESSING.getCode()));
        if (n > 0) {
            log.info("[pay] refund status updated no={} -> {} (channel={})",
                    as.getAfterSaleNo(), patch.getRefundStatus(), r.getStatus());
        }
        return n > 0;
    }

    /**
     * 主动同步一笔支付单（回调丢失时的兜底）。
     *
     * <p>只有"渠道明确说收到钱了"才会置为已支付 —— 查询失败/未支付一律不动状态，
     * 绝不能因为"查不到"就当成"付过了"。
     */
    @Override
    @Transactional
    public boolean syncPayment(String paymentNo) {
        Payment p = paymentMapper.selectOne(Wrappers.<Payment>lambdaQuery()
                .eq(Payment::getPaymentNo, paymentNo));
        if (p == null) {
            throw new BusinessException(ResultCode.PAYMENT_NOT_FOUND);
        }
        return syncPaymentInternal(p);
    }

    /**
     * 扫描待支付且够旧的支付单，主动去渠道同步。
     *
     * <p>为什么要求"够旧"（2 分钟）：用户刚点完支付正在跳转，这时候去查一定是未支付，
     * 白打一次接口；而回调延迟通常也就几秒。
     */
    @Override
    public int reconcilePendingPayments(int limit) {
        List<Payment> pending = paymentMapper.selectList(Wrappers.<Payment>lambdaQuery()
                .eq(Payment::getStatus, PaymentStatus.PENDING.getCode())
                .lt(Payment::getCreateTime, LocalDateTime.now().minusMinutes(2))
                .orderByAsc(Payment::getId)
                .last("LIMIT " + Math.max(1, limit)));
        int fixed = 0;
        for (Payment p : pending) {
            try {
                // 每笔单独一个事务：一笔失败不能拖累整批（渠道超时很常见）
                Boolean paid = transactionTemplate.execute(status -> syncPaymentInternal(p));
                if (Boolean.TRUE.equals(paid)) {
                    fixed += 1;
                }
            } catch (Exception e) {
                log.warn("[pay] payment reconcile failed no={} err={}", p.getPaymentNo(), e.toString());
            }
        }
        if (fixed > 0) {
            log.info("[pay] payment reconcile rescued {} of {} pending payments", fixed, pending.size());
        }
        return fixed;
    }

    /** 查一次渠道；只有渠道确认收款才落账，返回是否已支付 */
    private boolean syncPaymentInternal(Payment p) {
        if (Objects.equals(p.getStatus(), PaymentStatus.PAID.getCode())) {
            return true;
        }
        if (!Objects.equals(p.getStatus(), PaymentStatus.PENDING.getCode())) {
            return false;
        }
        PaymentChannel impl = channels.get(p.getChannel());
        if (impl == null || !impl.available()) {
            // 渠道没配置（例如 prod 关了 MOCK），跳过而不是报错——它本来就不该被同步
            return false;
        }
        PayModels.PayQueryResult r = impl.queryPayment(PayModels.PayQuery.builder()
                .paymentNo(p.getPaymentNo()).build());
        if (!r.isPaid()) {
            return false;
        }
        markPaid(p, r.getChannelTradeNo(), r.getAmount(), r.getRaw());
        log.info("[pay] payment rescued by query no={} status={}", p.getPaymentNo(), r.getStatus());
        return true;
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
