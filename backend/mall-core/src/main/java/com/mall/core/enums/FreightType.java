package com.mall.core.enums;

import lombok.Getter;

/** 运费模板类型 */
@Getter
public enum FreightType {

    /** 固定运费 */
    FIXED("固定运费"),
    /** 满额包邮，未满收基础运费 */
    FREE_OVER("满额包邮");

    private final String text;

    FreightType(String text) {
        this.text = text;
    }

    public static FreightType of(String code) {
        if (code == null) {
            return null;
        }
        for (FreightType t : values()) {
            if (t.name().equalsIgnoreCase(code)) {
                return t;
            }
        }
        return null;
    }
}