package com.mall.core.enums;

import lombok.Getter;

/**
 * 支付单状态。
 *
 * <p>流转很窄，且只有一条路能到 {@link #PAID}：
 * <pre>
 *   PENDING(0) ──渠道回调/模拟支付成功──> PAID(1)     // 终态
 *        ├──用户取消/重新下单──> CLOSED(2)            // 终态
 *        └──渠道返回失败──────> FAILED(3)             // 可以再发起新的支付单
 * </pre>
 *
 * <p>「只有 PENDING 能变 PAID」是用**带状态条件的 UPDATE** 保证的：渠道重复回调、
 * 用户连点两次「确认支付」都只会成功一次（见 PaymentServiceImpl.markPaid）。
 */
@Getter
public enum PaymentStatus {

    PENDING(0, "待支付"),
    PAID(1, "已支付"),
    CLOSED(2, "已关闭"),
    FAILED(3, "支付失败");

    private final int code;
    private final String text;

    PaymentStatus(int code, String text) {
        this.code = code;
        this.text = text;
    }

    public static String textOf(Integer code) {
        if (code == null) {
            return "";
        }
        for (PaymentStatus s : values()) {
            if (s.code == code) {
                return s.text;
            }
        }
        return "未知";
    }
}
