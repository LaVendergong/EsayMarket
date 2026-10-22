package com.mall.core.service.impl;

import com.mall.core.entity.Payment;
import com.mall.core.enums.PayChannel;
import com.mall.core.enums.PaymentStatus;
import com.mall.core.mapper.AfterSaleMapper;
import com.mall.core.mapper.OrderMapper;
import com.mall.core.mapper.PaymentMapper;
import com.mall.core.pay.PayModels;
import com.mall.core.pay.PayProperties;
import com.mall.core.pay.PaymentChannel;
import com.mall.core.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 支付查单兜底的核心不变量：**只有渠道明确说收到钱，才能把订单置为已支付**。
 *
 * <p>这条如果写错，对账任务会变成"批量白送"——比不做兜底危险得多。
 */
class PaymentServiceImplTest {

    private PaymentMapper paymentMapper;
    private OrderService orderService;
    private PaymentChannel channel;
    private PaymentServiceImpl service;

    @BeforeEach
    void setUp() {
        paymentMapper = mock(PaymentMapper.class);
        orderService = mock(OrderService.class);
        channel = mock(PaymentChannel.class);
        when(channel.channel()).thenReturn(PayChannel.MOCK);
        when(channel.available()).thenReturn(true);

        service = new PaymentServiceImpl(
                paymentMapper,
                mock(OrderMapper.class),
                orderService,
                new PayProperties(),
                mock(AfterSaleMapper.class),
                mock(PlatformTransactionManager.class),
                List.of(channel));
    }

    private Payment pendingPayment() {
        Payment p = new Payment();
        p.setId(1L);
        p.setPaymentNo("P20261022000001");
        p.setOrderId(7L);
        p.setOrderNo("20261022000001");
        p.setUserId(3L);
        p.setChannel(PayChannel.MOCK.getCode());
        p.setAmount(new BigDecimal("50.00"));
        p.setStatus(PaymentStatus.PENDING.getCode());
        return p;
    }

    @Test
    void sync_whenChannelSaysPaid_marksOrderPaid() {
        Payment p = pendingPayment();
        when(paymentMapper.selectOne(any())).thenReturn(p);
        when(paymentMapper.update(any(), any())).thenReturn(1);
        when(channel.queryPayment(any())).thenReturn(PayModels.PayQueryResult.builder()
                .paymentNo(p.getPaymentNo()).paid(true).status("SUCCESS")
                .channelTradeNo("4200001").amount(new BigDecimal("50.00")).build());

        assertThat(service.syncPayment(p.getPaymentNo())).isTrue();
        verify(orderService).markPaid(7L, PayChannel.MOCK.getCode(), p.getPaymentNo());
    }

    @Test
    void sync_whenChannelSaysNotPaid_changesNothing() {
        Payment p = pendingPayment();
        when(paymentMapper.selectOne(any())).thenReturn(p);
        when(channel.queryPayment(any())).thenReturn(PayModels.PayQueryResult.builder()
                .paymentNo(p.getPaymentNo()).paid(false).status("NOTPAY").build());

        assertThat(service.syncPayment(p.getPaymentNo())).isFalse();
        verify(orderService, never()).markPaid(any(), anyString(), anyString());
        verify(paymentMapper, never()).update(any(), any());
    }

    @Test
    void sync_whenPaymentAlreadyPaid_isIdempotentAndSkipsChannel() {
        Payment p = pendingPayment();
        p.setStatus(PaymentStatus.PAID.getCode());
        when(paymentMapper.selectOne(any())).thenReturn(p);

        assertThat(service.syncPayment(p.getPaymentNo())).isTrue();
        // 已支付不该再打渠道接口，也不该重复落账
        verify(channel, never()).queryPayment(any());
        verify(orderService, never()).markPaid(any(), anyString(), anyString());
    }

    @Test
    void sync_whenPaymentClosed_doesNotResurrectIt() {
        Payment p = pendingPayment();
        p.setStatus(PaymentStatus.CLOSED.getCode());
        when(paymentMapper.selectOne(any())).thenReturn(p);
        when(channel.queryPayment(any())).thenReturn(PayModels.PayQueryResult.builder()
                .paymentNo(p.getPaymentNo()).paid(true).build());

        assertThat(service.syncPayment(p.getPaymentNo())).isFalse();
        verify(orderService, never()).markPaid(any(), anyString(), anyString());
    }

    @Test
    void reconcile_ignoresPaymentsWhoseChannelIsUnavailable() {
        Payment p = pendingPayment();
        when(paymentMapper.selectList(any())).thenReturn(List.of(p));
        when(channel.available()).thenReturn(false);

        assertThat(service.reconcilePendingPayments(10)).isZero();
        verify(channel, never()).queryPayment(any());
        verify(orderService, never()).markPaid(eq(7L), anyString(), anyString());
    }
}
