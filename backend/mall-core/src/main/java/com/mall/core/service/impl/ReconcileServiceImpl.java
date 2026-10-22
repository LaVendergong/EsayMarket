package com.mall.core.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.common.exception.BusinessException;
import com.mall.common.result.PageResult;
import com.mall.common.result.ResultCode;
import com.mall.core.entity.Payment;
import com.mall.core.entity.ReconcileBatch;
import com.mall.core.entity.ReconcileDiff;
import com.mall.core.enums.PayChannel;
import com.mall.core.enums.PaymentStatus;
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
    private final Map<String, BillParser> parsers;

    public ReconcileServiceImpl(ReconcileBatchMapper batchMapper,
                                ReconcileDiffMapper diffMapper,
                                PaymentMapper paymentMapper,
                                List<BillParser> parserList) {
        this.batchMapper = batchMapper;
        this.diffMapper = diffMapper;
        this.paymentMapper = paymentMapper;
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

        // 本地侧：该渠道、该账期、已支付的支付单
        LocalDateTime from = billDate.atStartOfDay();
        LocalDateTime to = billDate.plusDays(1).atStartOfDay();
        Map<String, Payment> local = paymentMapper.selectList(Wrappers.<Payment>lambdaQuery()
                        .eq(Payment::getChannel, payChannel.getCode())
                        .eq(Payment::getStatus, PaymentStatus.PAID.getCode())
                        .ge(Payment::getPaidTime, from)
                        .lt(Payment::getPaidTime, to))
                .stream()
                .collect(Collectors.toMap(Payment::getPaymentNo, Function.identity(), (a, b) -> a, LinkedHashMap::new));

        List<ReconcileDiff> diffs = new ArrayList<>();
        int matched = 0;
        int missingLocal = 0;
        int amountMismatch = 0;
        Map<String, Boolean> seen = new LinkedHashMap<>();

        for (BillLine line : lines) {
            if ("REFUND".equalsIgnoreCase(line.getBizType())) {
                continue;   // v1 只对支付
            }
            Payment p = local.get(line.getOutTradeNo());
            if (p == null) {
                missingLocal += 1;
                diffs.add(diff(null, "MISSING_LOCAL", line.getOutTradeNo(), line.getChannelTradeNo(),
                        null, line.getAmount(), "渠道账单有该笔收款，本地无对应已支付记录"));
                continue;
            }
            seen.put(line.getOutTradeNo(), Boolean.TRUE);
            if (!sameAmount(p.getAmount(), line.getAmount())) {
                amountMismatch += 1;
                diffs.add(diff(null, "AMOUNT_MISMATCH", p.getPaymentNo(), line.getChannelTradeNo(),
                        p.getAmount(), line.getAmount(), "金额不一致"));
            } else {
                matched += 1;
            }
        }

        int missingChannel = 0;
        for (Payment p : local.values()) {
            if (!seen.containsKey(p.getPaymentNo())) {
                missingChannel += 1;
                diffs.add(diff(null, "MISSING_CHANNEL", p.getPaymentNo(), p.getChannelTradeNo(),
                        p.getAmount(), null, "本地已支付，渠道账单中没有该笔"));
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
        batch.setStatus(diffs.isEmpty() ? 1 : 0);
        batchMapper.insert(batch);

        for (ReconcileDiff d : diffs) {
            d.setBatchId(batch.getId());
            diffMapper.insert(d);
        }

        log.info("[reconcile] channel={} date={} lines={} matched={} missingLocal={} missingChannel={} amountMismatch={}",
                payChannel.getCode(), billDate, lines.size(), matched, missingLocal, missingChannel, amountMismatch);

        ReconcileBatchVO vo = ReconcileBatchVO.from(batch);
        vo.setDiffs(diffs.stream().map(ReconcileDiffVO::from).toList());
        return vo;
    }

    @Override
    public PageResult<ReconcileBatchVO> page(long pageNum, long pageSize) {
        IPage<ReconcileBatch> page = batchMapper.selectPage(new Page<>(pageNum, pageSize),
                Wrappers.<ReconcileBatch>lambdaQuery().orderByDesc(ReconcileBatch::getId));
        return PageResult.of(page.getTotal(), pageNum, pageSize,
                page.getRecords().stream().map(ReconcileBatchVO::from).toList());
    }

    @Override
    public ReconcileBatchVO detail(Long batchId) {
        ReconcileBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "对账批次不存在");
        }
        ReconcileBatchVO vo = ReconcileBatchVO.from(batch);
        vo.setDiffs(diffMapper.selectList(Wrappers.<ReconcileDiff>lambdaQuery()
                        .eq(ReconcileDiff::getBatchId, batchId)
                        .orderByAsc(ReconcileDiff::getId))
                .stream().map(ReconcileDiffVO::from).toList());
        return vo;
    }

    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return false;
        }
        return a.compareTo(b) == 0;
    }

    private static ReconcileDiff diff(Long batchId, String type, String paymentNo, String tradeNo,
                                      BigDecimal localAmount, BigDecimal channelAmount, String detail) {
        ReconcileDiff d = new ReconcileDiff();
        d.setBatchId(batchId);
        d.setDiffType(type);
        d.setPaymentNo(paymentNo);
        d.setChannelTradeNo(tradeNo);
        d.setLocalAmount(localAmount);
        d.setChannelAmount(channelAmount);
        d.setDetail(detail);
        return d;
    }
}
