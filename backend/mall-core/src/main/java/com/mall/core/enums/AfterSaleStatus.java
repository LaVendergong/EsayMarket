package com.mall.core.enums;

import lombok.Getter;

/**
 * 售后单状态。
 *
 * <p>状态机（4 个终态里 3 个是结束态）：
 * <pre>
 *   PENDING(0) ──商家同意──> APPROVED(1)   // 已退款，终态
 *        │
 *        ├──商家拒绝──> REJECTED(2)        // 订单状态恢复到申请前
 *        └──用户撤销──> CANCELED(3)        // 订单状态恢复到申请前
 * </pre>
 *
 * <p>只有 PENDING 可以流转，其余都是终态 —— 这一点在每个操作里用「带状态条件的 UPDATE」
 * 强制，而不是先查再改（并发下先查再改会重复处理）。
 */
@Getter
public enum AfterSaleStatus {

    PENDING(0, "待商家处理"),
    APPROVED(1, "已退款"),
    REJECTED(2, "商家已拒绝"),
    CANCELED(3, "用户已撤销");

    private final int code;
    private final String text;

    AfterSaleStatus(int code, String text) {
        this.code = code;
        this.text = text;
    }

    public static String textOf(Integer code) {
        if (code == null) {
            return "";
        }
        for (AfterSaleStatus s : values()) {
            if (s.code == code) {
                return s.text;
            }
        }
        return "未知";
    }
}
