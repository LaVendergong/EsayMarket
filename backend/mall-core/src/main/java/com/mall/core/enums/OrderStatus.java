package com.mall.core.enums;

import lombok.Getter;

/**
 * 订单状态机
 *
 * <pre>
 * 0 待支付 --支付--> 1 已支付 --发货--> 2 已发货 --确认收货--> 3 已完成
 *    \--用户取消/超时关单/后台关闭--> 4 已关闭（释放锁定库存）
 * </pre>
 * 状态流转一律用「带 status 条件的 UPDATE」实现，靠影响行数判断是否允许，保证并发下不会重复流转。
 */
@Getter
public enum OrderStatus {

    UNPAID(0, "待支付"),
    PAID(1, "已支付"),
    SHIPPED(2, "已发货"),
    FINISHED(3, "已完成"),
    CLOSED(4, "已关闭"),
    /** 有进行中的售后单：申请时从 已支付/已发货/已完成 流转进来，商家拒绝或用户撤销时退回原状态 */
    AFTER_SALE(5, "售后中"),
    /** 售后已同意并退款 */
    REFUNDED(6, "已退款");

    private final int code;
    private final String text;

    OrderStatus(int code, String text) {
        this.code = code;
        this.text = text;
    }

    public static String textOf(Integer code) {
        if (code == null) {
            return null;
        }
        for (OrderStatus s : values()) {
            if (s.code == code) {
                return s.text;
            }
        }
        return "未知";
    }
}
