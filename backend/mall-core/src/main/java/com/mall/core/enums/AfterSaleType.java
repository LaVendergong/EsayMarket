package com.mall.core.enums;

import lombok.Getter;

/**
 * 售后类型。
 *
 * <p>只做两种，因为它们的边界最清楚：
 * <ul>
 *   <li>{@link #REFUND_ONLY} 仅退款：货没退回来，**不恢复库存**</li>
 *   <li>{@link #RETURN_REFUND} 退货退款：货退回仓库，**恢复库存**</li>
 * </ul>
 * 「换货」暂不做 —— 它要再走一次发货流程，边界比退款复杂得多，
 * 与其做个半成品让人误以为能用，不如先不做（见 docs）。
 */
@Getter
public enum AfterSaleType {

    REFUND_ONLY(1, "仅退款"),
    RETURN_REFUND(2, "退货退款");

    private final int code;
    private final String text;

    AfterSaleType(int code, String text) {
        this.code = code;
        this.text = text;
    }

    public static String textOf(Integer code) {
        if (code == null) {
            return "";
        }
        for (AfterSaleType t : values()) {
            if (t.code == code) {
                return t.text;
            }
        }
        return "未知";
    }

    public static boolean isValid(Integer code) {
        for (AfterSaleType t : values()) {
            if (t.code == code) {
                return true;
            }
        }
        return false;
    }

    /** 退货退款才需要把库存加回去 */
    public static boolean needsStockRestore(Integer code) {
        return code != null && code == RETURN_REFUND.code;
    }
}
