package com.mall.core.service.impl;

import com.mall.common.exception.BusinessException;
import com.mall.core.entity.AfterSale;
import com.mall.core.entity.Payment;
import com.mall.core.entity.ReconcileBatch;
import com.mall.core.entity.ReconcileDiff;
import com.mall.core.enums.PayChannel;
import com.mall.core.enums.PaymentStatus;
import com.mall.core.enums.RefundStatus;
import com.mall.core.mapper.AfterSaleMapper;
import com.mall.core.mapper.PaymentMapper;
import com.mall.core.mapper.ReconcileBatchMapper;
import com.mall.core.mapper.ReconcileDiffMapper;
import com.mall.core.model.vo.ReconcileBatchVO;
import com.mall.core.pay.bill.NormalizedBillParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对账引擎的四类结果。
 *
 * <p>这里最要紧的不是"能对上"，而是**四类差异各自被正确归类**：
 * 渠道多收（MISSING_LOCAL）、本地多记（MISSING_CHANNEL）、金额不符（AMOUNT_MISMATCH）。
 * 归类错了，人工就会照着错的清单去查。
 */
class ReconcileServiceImplTest {

    private ReconcileBatchMapper batchMapper;
    private ReconcileDiffMapper diffMapper;
    private PaymentMapper paymentMapper;
    private AfterSaleMapper afterSaleMapper;
    private ReconcileServiceImpl service;

    @BeforeEach
    void setUp() {
        batchMapper = mock(ReconcileBatchMapper.class);
        diffMapper = mock(ReconcileDiffMapper.class);
        paymentMapper = mock(PaymentMapper.class);
        afterSaleMapper = mock(AfterSaleMapper.class);
        when(batchMapper.insert(any(ReconcileBatch.class))).thenAnswer(inv -> {
            inv.getArgument(0, ReconcileBatch.class).setId(99L);
            return 1;
        });
        when(diffMapper.insert(any(ReconcileDiff.class))).thenReturn(1);
        service = new ReconcileServiceImpl(batchMapper, diffMapper, paymentMapper, afterSaleMapper,
                List.of(new NormalizedBillParser()));
    }

    private Payment paid(String paymentNo, String amount) {
        Payment p = new Payment();
        p.setPaymentNo(paymentNo);
        p.setChannel(PayChannel.MOCK.getCode());
        p.setStatus(PaymentStatus.PAID.getCode());
        p.setAmount(new BigDecimal(amount));
        p.setPaidTime(LocalDateTime.now());
        return p;
    }

    @Test
    void reconcile_classifiesAllFourOutcomes() {
        // 本地：P1 100（对得上）、P3 99（金额不符）、P4 10（账单里没有）
        when(paymentMapper.selectList(any())).thenReturn(List.of(
                paid("P1", "100.00"), paid("P3", "99.00"), paid("P4", "10.00")));
        // 账单：P1 100、P3 100（金额不符）、P2 50（本地没有）
        String bill = """
                out_trade_no,channel_trade_no,amount,status,biz_type
                P1,T1,100.00,SUCCESS,PAY
                P3,T3,100.00,SUCCESS,PAY
                P2,T2,50.00,SUCCESS,PAY
                """;

        ReconcileBatchVO vo = service.reconcile(PayChannel.MOCK.getCode(), LocalDate.now(), bill);

        assertThat(vo.getTotalLines()).isEqualTo(3);
        assertThat(vo.getMatched()).isEqualTo(1);
        assertThat(vo.getMissingLocal()).isEqualTo(1);
        assertThat(vo.getAmountMismatch()).isEqualTo(1);
        assertThat(vo.getMissingChannel()).isEqualTo(1);
        assertThat(vo.getStatus()).isZero();
        assertThat(vo.getDiffs()).extracting("diffType")
                .containsExactlyInAnyOrder("MISSING_LOCAL", "AMOUNT_MISMATCH", "MISSING_CHANNEL");
    }

    @Test
    void reconcile_marksBatchCleanWhenEverythingMatches() {
        when(paymentMapper.selectList(any())).thenReturn(List.of(paid("P1", "100.00")));
        String bill = """
                out_trade_no,channel_trade_no,amount,status,biz_type
                P1,T1,100.00,SUCCESS,PAY
                """;

        ReconcileBatchVO vo = service.reconcile(PayChannel.MOCK.getCode(), LocalDate.now(), bill);

        assertThat(vo.getMatched()).isEqualTo(1);
        assertThat(vo.getStatus()).isEqualTo(1);
        assertThat(vo.getDiffs()).isEmpty();
    }

    @Test
    void reconcile_ignoresRefundLines() {
        when(paymentMapper.selectList(any())).thenReturn(List.of(paid("P1", "100.00")));
        String bill = """
                out_trade_no,channel_trade_no,amount,status,biz_type
                P1,T1,100.00,SUCCESS,PAY
                P1,RT1,100.00,REFUND,REFUND
                """;

        ReconcileBatchVO vo = service.reconcile(PayChannel.MOCK.getCode(), LocalDate.now(), bill);

        // 退款行不参与支付对账，否则会凭空多出一条"渠道有本地无"
        assertThat(vo.getMatched()).isEqualTo(1);
        assertThat(vo.getMissingLocal()).isZero();
        assertThat(vo.getStatus()).isEqualTo(1);
    }

    @Test
    void reconcile_refunds_areMatchedByRefundNo() {
        when(paymentMapper.selectList(any())).thenReturn(new ArrayList<>());
        // 本地：RF1 已退 100；RF2 已退 50（账单里没有）
        when(afterSaleMapper.selectList(any())).thenReturn(List.of(
                refunded("RF1", "100.00"), refunded("RF2", "50.00")));
        // 账单：RF1 退 100（对得上）、RF3 退 30（本地没有）
        String bill = """
                out_trade_no,channel_trade_no,amount,status,biz_type,refund_no
                RF1,MOCKR1,100.00,SUCCESS,REFUND,RF1
                RF3,MOCKR3,30.00,SUCCESS,REFUND,RF3
                """;

        ReconcileBatchVO vo = service.reconcile(PayChannel.MOCK.getCode(), LocalDate.now(), bill);

        assertThat(vo.getRefundTotalLines()).isEqualTo(2);
        assertThat(vo.getRefundMatched()).isEqualTo(1);
        assertThat(vo.getRefundMissingLocal()).isEqualTo(1);
        assertThat(vo.getRefundMissingChannel()).isEqualTo(1);
        assertThat(vo.getStatus()).isZero();
        assertThat(vo.getDiffs()).extracting("bizType").containsOnly("REFUND");
        assertThat(vo.getDiffs()).extracting("refundNo").contains("RF3", "RF2");
    }

    @Test
    void reconcile_refundAmountMismatchIsFlagged() {
        when(paymentMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(afterSaleMapper.selectList(any())).thenReturn(List.of(refunded("RF1", "100.00")));
        String bill = """
                out_trade_no,channel_trade_no,amount,status,biz_type,refund_no
                RF1,MOCKR1,80.00,SUCCESS,REFUND,RF1
                """;

        ReconcileBatchVO vo = service.reconcile(PayChannel.MOCK.getCode(), LocalDate.now(), bill);

        assertThat(vo.getRefundAmountMismatch()).isEqualTo(1);
        assertThat(vo.getDiffs().get(0).getLocalAmount()).isEqualByComparingTo("100.00");
        assertThat(vo.getDiffs().get(0).getChannelAmount()).isEqualByComparingTo("80.00");
    }

    private AfterSale refunded(String refundNo, String amount) {
        AfterSale as = new AfterSale();
        as.setAfterSaleNo("AS-" + refundNo);
        as.setRefundNo(refundNo);
        as.setRefundChannel(PayChannel.MOCK.getCode());
        as.setRefundStatus(RefundStatus.SUCCESS.getCode());
        as.setRefundAmount(new BigDecimal(amount));
        as.setRefundTime(LocalDateTime.now());
        return as;
    }

    @Test
    void reconcile_failsLoudlyWhenBillIsEmpty() {
        // 解析不出数据时必须报错：静默生成"零差异"报告比不核对更危险
        assertThatThrownBy(() -> service.reconcile(PayChannel.MOCK.getCode(), LocalDate.now(), "garbage"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("没有解析出任何流水");
    }

    @Test
    void reconcile_rejectsUnknownChannel() {
        assertThatThrownBy(() -> service.reconcile("PAYPAL", LocalDate.now(), "x"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("渠道不正确");
    }

    @Test
    void reconcile_persistsBatchAndDiffs() {
        when(paymentMapper.selectList(any())).thenReturn(new ArrayList<>());
        String bill = """
                out_trade_no,channel_trade_no,amount,status,biz_type
                P9,T9,66.00,SUCCESS,PAY
                """;

        service.reconcile(PayChannel.MOCK.getCode(), LocalDate.now(), bill);

        verify(batchMapper).insert(any(ReconcileBatch.class));
        verify(diffMapper).insert(any(ReconcileDiff.class));
    }
}
