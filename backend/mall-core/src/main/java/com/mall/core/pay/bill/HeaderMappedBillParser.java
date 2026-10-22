package com.mall.core.pay.bill;

import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 按**表头名**映射列的对账账单解析器。
 *
 * <p>为什么不写死列序号：账单格式经常微调（多一列"费率备注"就全错位），
 * 按表头名找列比按下标稳得多。找不到表头就返回空（对账会显示"账单为空"，而不是悄悄算错）。
 *
 * <p>子类只需要提供各字段可能的表头别名。
 */
@Slf4j
public abstract class HeaderMappedBillParser implements BillParser {

    /** 商户订单号的表头候选 */
    protected abstract String[] outTradeNoHeaders();

    /** 渠道交易号的表头候选 */
    protected abstract String[] channelTradeNoHeaders();

    /** 金额的表头候选（单位：元） */
    protected abstract String[] amountHeaders();

    /** 状态的表头候选（可空，空则默认按成功处理） */
    protected String[] statusHeaders() {
        return new String[]{};
    }

    /** 出现这些表头且值非空/非 0，说明这行是退款 */
    protected String[] refundHeaders() {
        return new String[]{"退款金额", "退款单号", "微信退款单号"};
    }

    /**
     * 商户退款单号的表头候选。
     *
     * <p>退款对账靠它和本地 {@code t_after_sale.refund_no} 匹配 —— 我们发给渠道的
     * {@code out_refund_no} 会原样出现在账单里。
     */
    protected String[] refundNoHeaders() {
        return new String[]{"商户退款单号", "退款批次号/请求号", "退款单号"};
    }

    @Override
    public List<BillLine> parse(String content) {
        List<BillLine> out = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return out;
        }
        String[] lines = content.split("\r?\n");
        int headerIndex = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("商户订单号")) {
                headerIndex = i;
                break;
            }
        }
        if (headerIndex < 0) {
            log.warn("[reconcile] bill has no header row containing 商户订单号, channel={}", channel());
            return out;
        }
        List<String> header = CsvLine.split(lines[headerIndex]);
        int iOut = indexOf(header, outTradeNoHeaders());
        int iTrade = indexOf(header, channelTradeNoHeaders());
        int iAmount = indexOf(header, amountHeaders());
        int iStatus = indexOf(header, statusHeaders());
        int[] iRefund = indexesOf(header, refundHeaders());
        int iRefundNo = indexOf(header, refundNoHeaders());
        if (iOut < 0 || iAmount < 0) {
            log.warn("[reconcile] bill header missing required column, channel={}", channel());
            return out;
        }

        for (int i = headerIndex + 1; i < lines.length; i++) {
            String raw = lines[i];
            if (raw.isBlank()) {
                continue;
            }
            List<String> cells = CsvLine.split(raw);
            String outTradeNo = cellAt(cells, iOut);
            if (outTradeNo.isEmpty() || outTradeNo.startsWith("#") || outTradeNo.contains("汇总")
                    || outTradeNo.contains("总计") || outTradeNo.contains("总交易")) {
                continue;   // 表头重复行 / 汇总行 / 注释行
            }
            BigDecimal amount = parseAmount(cellAt(cells, iAmount));
            if (amount == null) {
                continue;
            }
            out.add(BillLine.builder()
                    .outTradeNo(outTradeNo)
                    .channelTradeNo(iTrade < 0 ? null : cellAt(cells, iTrade))
                    .amount(amount)
                    .status(iStatus < 0 ? "SUCCESS" : cellAt(cells, iStatus))
                    .bizType(isRefund(cells, iRefund) ? "REFUND" : "PAY")
                    .build());
        }
        return out;
    }

    private static boolean isRefund(List<String> cells, int[] indexes) {
        for (int idx : indexes) {
            String v = cellAt(cells, idx);
            if (v.isEmpty() || "0".equals(v) || "0.00".equals(v)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static BigDecimal parseAmount(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(s.replace(",", "").replace("¥", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String cellAt(List<String> cells, int index) {
        if (index < 0 || index >= cells.size()) {
            return "";
        }
        String v = cells.get(index);
        return v == null ? "" : v.trim();
    }

    private static int indexOf(List<String> header, String[] aliases) {
        for (int i = 0; i < header.size(); i++) {
            String h = CsvLine.clean(header.get(i));
            for (String alias : aliases) {
                if (h.equals(alias)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int[] indexesOf(List<String> header, String[] aliases) {
        List<Integer> found = new ArrayList<>();
        for (int i = 0; i < header.size(); i++) {
            String h = CsvLine.clean(header.get(i));
            for (String alias : aliases) {
                if (h.equals(alias)) {
                    found.add(i);
                }
            }
        }
        return found.stream().mapToInt(Integer::intValue).toArray();
    }
}
