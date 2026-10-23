package com.mall.core.enums;

import lombok.Getter;

/**
 * 用户券状态。
 *
 * <p>下单即锁定（USED），取消/超时关单退回（UNUSED），支付成功后保持 USED。
 * 这样做的好处：一张券在同一时刻只能被一个未支付订单占用，避免"两个订单用同一张券"。
 */
@Getter
public enum UserCouponStatus {

    UNUSED(0, "未使用"),
    USED(1, "已使用"),
    EXPIRED(2, "已过期");

    private final int code;
    private final String text;

    UserCouponStatus(int code, String text) {
        this.code = code;
        this.text = text;
    }

    public static String textOf(Integer code) {
        if (code == null) {
            return "";
        }
        for (UserCouponStatus s : values()) {
            if (s.code == code) {
                return s.text;
            }
        }
        return "未知";
    }
}