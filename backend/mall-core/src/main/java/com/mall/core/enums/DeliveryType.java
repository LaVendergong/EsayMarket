package com.mall.core.enums;

import lombok.Getter;

/** 虚拟商品的发放方式 */
@Getter
public enum DeliveryType {

    /** 卡密：从卡密池抽一张，查看即视为已使用 */
    CARD_KEY(1, "卡密"),
    /** 外链：商品上配好一个下载地址（不做对象存储，只放外链） */
    LINK(2, "外链");

    private final int code;
    private final String text;

    DeliveryType(int code, String text) {
        this.code = code;
        this.text = text;
    }

    public static DeliveryType of(Integer code) {
        if (code == null) {
            return null;
        }
        for (DeliveryType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        return null;
    }

    public static String textOf(Integer code) {
        DeliveryType t = of(code);
        return t == null ? "" : t.text;
    }
}
