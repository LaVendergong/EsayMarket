package com.mall.core.enums;

import lombok.Getter;

/**
 * 退款执行状态（和售后单状态是两回事）。
 *
 * <p>为什么分开：售后单的"已同意"是**业务决定**，退款是**外部动作**。渠道可能拒绝、
 * 也可能只是受理（微信 PROCESSING 要等异步结果）。分开记才能做到：
 * 既不骗用户说钱到了，又让客服能安全重试（重试复用同一个 refund_no，渠道幂等）。
 */
@Getter
public enum RefundStatus {

    NONE(0, "未退款"),
    PROCESSING(1, "退款中"),
    SUCCESS(2, "退款成功"),
    FAILED(3, "退款失败");

    private final int code;
    private final String text;

    RefundStatus(int code, String text) {
        this.code = code;
        this.text = text;
    }

    public static String textOf(Integer code) {
        if (code == null) {
            return "";
        }
        for (RefundStatus s : values()) {
            if (s.code == code) {
                return s.text;
            }
        }
        return "未知";
    }
}
