package com.mall.core.service.impl;

import com.mall.core.mapper.ReportMapper;
import com.mall.core.model.vo.DailyStatVO;
import com.mall.core.model.vo.ReportSummaryVO;
import com.mall.core.model.vo.TopProductVO;
import com.mall.core.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

    private static final int MAX_DAYS = 365;

    private final ReportMapper reportMapper;

    @Override
    public ReportSummaryVO summary(int days) {
        LocalDateTime to = LocalDateTime.now();
        LocalDateTime from = fromOf(days);

        BigDecimal gmv = nz(reportMapper.gmv(from, to));
        BigDecimal refund = nz(reportMapper.refundAmount(from, to));
        long orders = reportMapper.orderCount(from, to);
        long received = reportMapper.couponReceived(from, to);
        long used = reportMapper.couponUsed(from, to);

        ReportSummaryVO vo = new ReportSummaryVO();
        vo.setGmv(gmv);
        vo.setRefundAmount(refund);
        vo.setNetGmv(gmv.subtract(refund));
        vo.setOrderCount(orders);
        vo.setBuyerCount(reportMapper.buyerCount(from, to));
        // 没有订单时客单价是 0，不是 null：前端不用再判空
        vo.setAvgOrderAmount(orders == 0 ? BigDecimal.ZERO
                : gmv.divide(BigDecimal.valueOf(orders), 2, RoundingMode.HALF_UP));
        vo.setNewUsers(reportMapper.newUsers(from, to));
        vo.setCouponReceived(received);
        vo.setCouponUsed(used);
        vo.setCouponUseRate(received == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(used).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(received), 1, RoundingMode.HALF_UP));
        return vo;
    }

    @Override
    public List<DailyStatVO> daily(int days) {
        return reportMapper.daily(fromOf(days), LocalDateTime.now());
    }

    @Override
    public List<TopProductVO> topProducts(int days, int limit) {
        int n = Math.min(Math.max(limit, 1), 50);
        return reportMapper.topProducts(fromOf(days), LocalDateTime.now(), n);
    }

    /** 近 N 天含今天：从 (今天 - N + 1) 的 00:00 开始 */
    private LocalDateTime fromOf(int days) {
        int d = Math.min(Math.max(days, 1), MAX_DAYS);
        return LocalDate.now().minusDays(d - 1L).atStartOfDay();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
