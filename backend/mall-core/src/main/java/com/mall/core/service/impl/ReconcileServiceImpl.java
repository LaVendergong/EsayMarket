package com.mall.core.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.common.exception.BusinessException;
import com.mall.common.result.PageResult;
import com.mall.common.result.ResultCode;
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
import com.mall.core.model.vo.ReconcileDiffVO;
import com.mall.core.pay.bill.BillLine;
import com.mall.core.pay.bill.BillParser;
import com.mall.core.service.ReconcileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 对账引擎：**渠道账单 vs 本地支付记录**逐笔比对。
 *
 * <p>为什么单笔查单（上一轮）不够：查单必须先知道"要查哪一笔"，而真正的事故形态是
 * **我们根本不知道有这笔钱**（回调丢了、落库失败、渠道多收）。只有拿账单逐笔对，才能发现它。
 *
 * <p>三类差异的含义完全不同，处理方式也不同：
 * <ul>
 *   <li><b>MISSING_LOCAL</b>：渠道收了钱、本地没记账 —— 最危险，必须补记</li>
 *   <li><b>MISSING_CHANNEL</b>：本地记了已支付、渠道账单里没有 —— 可能是本地伪造/测试数据，
 *       也可能是账单缺页，要人工核实</li>
 *   <li><b>AMOUNT_MISMATCH</b>：两边都有但金额不同 —— 通常是优惠/手续费口径问题</li>
 * </ul>
 *
 * <p>v1 只对**支付**；退款对账（退款金额、退款状态）留到下一版。
 */
@Slf4j
@Service
public class ReconcileServiceImpl implements ReconcileService {

    private final ReconcileBatchMapper batchMapper;
    private final ReconcileDiffMapper diffMapper;
    private final PaymentMapper paymentMapper;
    /** 退款对账要读售后单 */
    private final AfterSaleMapper afterSaleMapper;
    private final Map<String, BillParser> parsers;

    public ReconcileServiceImpl(ReconcileBatchMapper batchMapper,
                                ReconcileDiffMapper diffMapper,
                                PaymentMapper paymentMapper,
                                AfterSaleMapper afterSaleMapper,
                                List<BillParser> parserList) {
        this.batchMapper = batchMapper;
        this.diffMapper = diffMapper;
        this.paymentMapper = paymentMapper;
        this.afterSaleMapper = afterSaleMapper;
        this.parsers = parserList.stream()
                .collect(Collectors.toMap(p -> p.channel().getCode(), Function.identity()));
    }

    @Override
    @Transactional
    public ReconcileBatchVO reconcile(String channel, LocalDate billDate, String content) {
        PayChannel payChannel = PayChannel.of(channel);
        if (payChannel == null) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "渠道不正确");
        }
        BillParser parser = parsers.get(payChannel.getCode());
        if (parser == null) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "该渠道没有账单解析器");
        }
        List<BillLine> lines = parser.parse(content);
        if (lines.isEmpty()) {
            // 解析不出数据就明确失败：静默生成一份"零差异"报告比不核对更危险
            throw new BusinessException(ResultCode.VALIDATE_FAILED,
                    "账单没有解析出任何流水，请检查文件格式（是否为空/表头不匹配）");
        }

        List<BillLine> payLines = lines.stream()
                .filter(l -> !"REFUND".equalsIgnoreCase(l.getBizType())).toList();
        List<BillLine> refundLines = lines.stream()
                .filter(l -> "REFUND".equalsIgnoreCase(l.getBizType())).toList();

        LocalDateTime from = billDate.atStartOfDay();
        LocalDateTime to = billDate.plusDays(1).atStartOfDay();
        List<ReconcileDiff> diffs = new ArrayList<>();

        // ---------------------------------------------------------------- 支付侧
        Map<String, Payment> local = paymentMapper.selectList(Wrappers.<Payment>lambdaQuery()
                        .eq(Payment::getChannel, payChannel.getCode())
                        .eq(Payment::getStatus, PaymentStatus.PAID.getCode())
                        .ge(Payment::getPaidTime, from)
                        .lt(Payment::getPaidTime, to))
                .stream()
                .collect(Collectors.toMap(Payment::getPaymentNo, Function.identity(), (a, b) -> a, LinkedHashMap::new));

        int matched = 0;
        int missingLocal = 0;
        int amountMismatch = 0;
        Map<String, Boolean> seen = new LinkedHashMap<>();

        for (BillLine line : payLines) {
            Payment p = local.get(line.getOutTradeNo());
            if (p == null) {
                missingLocal += 1;
                diffs.add(diff("PAY", "MISSING_LOCAL", line.getOutTradeNo(), null, line.getChannelTradeNo(),
                        null, line.getAmount(), "渠道账单有该笔收款，本地无对应已支付记录"));
                continue;
            }
            seen.put(line.getOutTradeNo(), Boolean.TRUE);
            if (!sameAmount(p.getAmount(), line.getAmount())) {
                amountMismatch += 1;
                diffs.add(diff("PAY", "AMOUNT_MISMATCH", p.getPaymentNo(), null, line.getChannelTradeNo(),
                        p.getAmount(), line.getAmount(), "金额不一致"));
            } else {
                matched += 1;
            }
        }

        int missingChannel = 0;
        for (Payment p : local.values()) {
            if (!seen.containsKey(p.getPaymentNo())) {
                missingChannel += 1;
                diffs.add(diff("PAY", "MISSING_CHANNEL", p.getPaymentNo(), null, p.getChannelTradeNo(),
                        p.getAmount(), null, "本地已支付，渠道账单中没有该笔"));
            }
        }

        // ---------------------------------------------------------------- 退款侧
        // 退款按「商户退款单号」匹配：我们发给渠道的 out_refund_no 会原样出现在账单里。
        int refundMatched = 0;
        int refundMissingLocal = 0;
        int refundAmountMismatch = 0;
        int refundMissingChannel = 0;

        if (!refundLines.isEmpty()) {
            Map<String, AfterSale> localRefunds = afterSaleMapper.selectList(Wrappers.<AfterSale>lambdaQuery()
                            .eq(AfterSale::getRefundChannel, payChannel.getCode())
                            .in(AfterSale::getRefundStatus,
                                    RefundStatus.PROCESSING.getCode(), RefundStatus.SUCCESS.getCode())
                            .isNotNull(AfterSale::getRefundNo)
                            .ge(AfterSale::getRefundTime, from)
                            .lt(AfterSale::getRefundTime, to))
                    .stream()
                    .collect(Collectors.toMap(AfterSale::getRefundNo, Function.identity(), (a, b) -> a, LinkedHashMap::new));

            Map<String, Boolean> seenRefund = new LinkedHashMap<>();
            for (BillLine line : refundLines) {
                String refundNo = line.getOutRefundNo();
                if (refundNo == null || refundNo.isBlank()) {
                    // 账单里的退款行没有商户退款单号就无法可靠匹配，跳过并告警（而不是算成差异制造噪音）
                    log.warn("[reconcile] refund line without merchant refund no, skipped: outTradeNo={}",
                            line.getOutTradeNo());
                    continue;
                }
                AfterSale as = localRefunds.get(refundNo);
                if (as == null) {
                    refundMissingLocal += 1;
                    diffs.add(refundDiff("MISSING_LOCAL", refundNo, null, line.getAmount(),
                            "渠道账单有退款，本地无对应售后记录（钱退了但没记账）"));
                    continue;
                }
                seenRefund.put(refundNo, Boolean.TRUE);
                if (!sameAmount(as.getRefundAmount(), line.getAmount())) {
                    refundAmountMismatch += 1;
                    diffs.add(refundDiff("AMOUNT_MISMATCH", refundNo, as.getRefundAmount(), line.getAmount(),
                            "退款金额不一致"));
                } else {
                    refundMatched += 1;
                }
            }
            for (AfterSale as : localRefunds.values()) {
                if (!seenRefund.containsKey(as.getRefundNo())) {
                    refundMissingChannel += 1;
                    diffs.add(refundDiff("MISSING_CHANNEL", as.getRefundNo(), as.getRefundAmount(), null,
                            "本地已退款，渠道账单中没有该笔退款"));
                }
            }
        }

        ReconcileBatch batch = new ReconcileBatch();
        batch.setChannel(payChannel.getCode());
        batch.setBillDate(billDate);
        batch.setTotalLines(lines.size());
        batch.setMatched(matched);
        batch.setMissingLocal(missingLocal);
        batch.setMissingChannel(missingChannel);
        batch.setAmountMismatch(amountMismatch);
        batch.setRefundTotalLines(refundLines.size());
        batch.setRefundMatched(refundMatched);
        batch.setRefundMissingLocal(refundMissingLocal);
        batch.setRefundMissingChannel(refundMissingChannel);
        batch.setRefundAmountMismatch(refundAmountMismatch);
        batch.setStatus(diffs.isEmpty() ? 1 : 0);
        batchMapper.insert(batch);

        for (ReconcileDiff d : diffs) {
            d.setBatchId(batch.getId());
            diffMapper.insert(d);
        }

        log.info("[reconcile] channel={} date={} payLines={} matched={} missingLocal={} missingChannel={} "
                        + "amountMismatch={} | refundLines={} refundMatched={} refundMissingLocal={} "
                        + "refundMissingChannel={} refundAmountMismatch={}",
                payChannel.getCode(), billDate, payLines.size(), matched, missingLocal, missingChannel, amountMismatch,
                refundLines.size(), refundMatched, refundMissingLocal, refundMissingChannel, refundAmountMismatch);

        ReconcileBatchVO vo = ReconcileBatchVO.from(batch);
        vo.setDiffs(diffs.stream().map(ReconcileDiffVO::from).toList());
        return vo;
    }

    @Override
    @Transactional
    public ReconcileBatchVO handleDiff(Long diffId, String action, String remark, String operator) {
        String normalized = action == null ? "" : action.trim().toUpperCase();
        if (!"CONFIRM".equals(normalized) && !"IGNORE".equals(normalized)) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "处理动作只能是 CONFIRM 或 IGNORE");
        }
        ReconcileDiff d = diffMapper.selectById(diffId);
        if (d == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "差异记录不存在");
        }
        // 只有"待处理"能被处理：并发/重复点击时只有一次生效（条件 UPDATE + 影响行数）
        ReconcileDiff patch = new ReconcileDiff();
        patch.setHandleStatus("IGNORE".equals(normalized) ? 2 : 1);
        patch.setHandleAction(normalized);
        patch.setHandleRemark(remark == null || remark.isBlank() ? null : remark.trim());
        patch.setHandledBy(operator);
        patch.setHandleTime(LocalDateTime.now());
        int n = diffMapper.update(patch, Wrappers.<ReconcileDiff>lambdaUpdate()
                .eq(ReconcileDiff::getId, diffId)
                .eq(ReconcileDiff::getHandleStatus, 0));
        if (n == 0) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "该差异已被处理过了");
        }
        log.info("[reconcile] diff handled id={} action={} by={}", diffId, normalized, operator);
        return detail(d.getBatchId());
    }

    /** 每个批次还有几条没处理（列表用，一次分组查询，避免 N+1） */
    private Map<Long, Integer> unhandledCounts(List<Long> batchIds) {
        if (batchIds == null || batchIds.isEmpty()) {
            return Map.of();
        }
        List<ReconcileDiff> rows = diffMapper.selectList(Wrappers.<ReconcileDiff>lambdaQuery()
                .select(ReconcileDiff::getBatchId)
                .in(ReconcileDiff::getBatchId, batchIds)
                .eq(ReconcileDiff::getHandleStatus, 0));
        return rows.stream().collect(Collectors.groupingBy(ReconcileDiff::getBatchId,
                Collectors.summingInt(x -> 1)));
    }

    @Override
    public PageResult<ReconcileBatchVO> page(long pageNum, long pageSize) {
        IPage<ReconcileBatch> page = batchMapper.selectPage(new Page<>(pageNum, pageSize),
                Wrappers.<ReconcileBatch>lambdaQuery().orderByDesc(ReconcileBatch::getId));
        List<ReconcileBatchVO> vos = page.getRecords().stream().map(ReconcileBatchVO::from).toList();
        Map<Long, Integer> counts = unhandledCounts(page.getRecords().stream().map(ReconcileBatch::getId).toList());
        vos.forEach(v -> v.setUnhandledCount(counts.getOrDefault(v.getId(), 0)));
        return PageResult.of(page.getTotal(), pageNum, pageSize, vos);
    }

    @Override
    public ReconcileBatchVO detail(Long batchId) {
        ReconcileBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "对账批次不存在");
        }
        ReconcileBatchVO vo = ReconcileBatchVO.from(batch);
        List<ReconcileDiffVO> diffs = diffMapper.selectList(Wrappers.<ReconcileDiff>lambdaQuery()
                        .eq(ReconcileDiff::getBatchId, batchId)
                        .orderByAsc(ReconcileDiff::getId))
                .stream().map(ReconcileDiffVO::from).toList();
        vo.setDiffs(diffs);
        vo.setUnhandledCount((int) diffs.stream()
                .filter(d -> d.getHandleStatus() == null || d.getHandleStatus() == 0).count());
        return vo;
    }

    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return false;
        }
        return a.compareTo(b) == 0;
    }

    private static ReconcileDiff diff(String bizType, String type, String paymentNo, String refundNo,
                                      String tradeNo, BigDecimal localAmount, BigDecimal channelAmount, String detail) {
        ReconcileDiff d = new ReconcileDiff();
        d.setBizType(bizType);
        d.setDiffType(type);
        d.setPaymentNo(paymentNo);
        d.setRefundNo(refundNo);
        d.setChannelTradeNo(tradeNo);
        d.setLocalAmount(localAmount);
        d.setChannelAmount(channelAmount);
        d.setDetail(detail);
        return d;
    }

    /** 退款差异：业务主键是退款单号，没有 payment_no 可填 */
    private static ReconcileDiff refundDiff(String type, String refundNo,
                                            BigDecimal localAmount, BigDecimal channelAmount, String detail) {
        return diff("REFUND", type, null, refundNo, null, localAmount, channelAmount, detail);
    }
}
