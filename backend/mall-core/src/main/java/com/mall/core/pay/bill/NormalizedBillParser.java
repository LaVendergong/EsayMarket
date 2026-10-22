package com.mall.core.pay.bill;

import com.mall.core.enums.PayChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 归一化账单解析（CSV，表头英文）。
 *
 * <p>用途有两个：
 * <ul>
 *   <li>模拟渠道（MOCK）的对账 —— 本地就能把整条对账链路跑通</li>
 *   <li>手工整理/转换后的账单（真实渠道格式没核实前，用这个格式最稳）</li>
 * </ul>
 *
 * <p>格式（第一行必须是表头）：
 * <pre>
 * out_trade_no,channel_trade_no,amount,status,biz_type
 * P20261022000001,MOCKT-1,100.00,SUCCESS,PAY
 * </pre>
 */
@Slf4j
@Component
public class NormalizedBillParser implements BillParser {

    @Override
    public PayChannel channel() {
        return PayChannel.MOCK;
    }

    @Override
    public List<BillLine> parse(String content) {
        List<BillLine> out = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return out;
        }
        String[] lines = content.split("\r?\n");
        int iOut = -1;
        int iTrade = -1;
        int iAmount = -1;
        int iStatus = -1;
        int iType = -1;
        for (int i = 0; i < lines.length; i++) {
            List<String> header = CsvLine.split(lines[i]);
            if (header.size() >= 3 && "out_trade_no".equalsIgnoreCase(header.get(0))) {
                iOut = 0;
                iTrade = header.indexOf("channel_trade_no");
                iAmount = header.indexOf("amount");
                iStatus = header.indexOf("status");
                iType = header.indexOf("biz_type");
                // 从下一行开始读数据
                for (int j = i + 1; j < lines.length; j++) {
                    addLine(out, lines[j], iOut, iTrade, iAmount, iStatus, iType);
                }
                return out;
            }
        }
        log.warn("[reconcile] normalized bill has no header (out_trade_no,...)");
        return out;
    }

    private static void addLine(List<BillLine> out, String raw, int iOut, int iTrade,
                                int iAmount, int iStatus, int iType) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        List<String> cells = CsvLine.split(raw);
        String outTradeNo = cell(cells, iOut);
        if (outTradeNo.isEmpty() || "out_trade_no".equalsIgnoreCase(outTradeNo)) {
            return;
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(cell(cells, iAmount).replace("¥", ""));
        } catch (NumberFormatException e) {
            return;
        }
        out.add(BillLine.builder()
                .outTradeNo(outTradeNo)
                .channelTradeNo(cell(cells, iTrade))
                .amount(amount)
                .status(cell(cells, iStatus).isEmpty() ? "SUCCESS" : cell(cells, iStatus))
                .bizType(cell(cells, iType).isEmpty() ? "PAY" : cell(cells, iType).toUpperCase())
                .build());
    }

    private static String cell(List<String> cells, int index) {
        if (index < 0 || index >= cells.size()) {
            return "";
        }
        return cells.get(index) == null ? "" : cells.get(index).trim();
    }
}
