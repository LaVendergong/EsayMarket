package com.mall.core.enums;

import lombok.Getter;

/**
 * 商品类型。
 *
 * <p>决定订单走哪条链路：实物要地址、要运费、后台发货后确认收货；
 * 虚拟商品不要地址、无运费、支付即发放并直接完成。
 */
@Getter
public enum ProductType {

    PHYSICAL(1, "实物"),
    VIRTUAL(2, "虚拟");

    private final int code;
    private final String text;

    ProductType(int code, String text) {
        this.code = code;
        this.text = text;
    }

    public static ProductType of(Integer code) {
        if (code == null) {
            return PHYSICAL;
        }
        for (ProductType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        return PHYSICAL;
    }

    public static String textOf(Integer code) {
        return of(code).text;
    }
}
