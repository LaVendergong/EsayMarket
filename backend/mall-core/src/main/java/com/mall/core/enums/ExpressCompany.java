package com.mall.core.enums;

import lombok.Getter;

/**
 * 快递公司。
 *
 * <p>存 code 而不是中文名：后台要按快递公司做聚合，中文名一改历史数据就对不上。
 * 自由文本看着省事，但会产生「顺丰」「顺丰速运」「SF」三种写法。
 */
@Getter
public enum ExpressCompany {

    SF("顺丰速运"),
    ZTO("中通快递"),
    YTO("圆通速递"),
    YD("韵达速递"),
    JD("京东物流"),
    EMS("邮政EMS"),
    OTHER("其他");

    private final String text;

    ExpressCompany(String text) {
        this.text = text;
    }

    public static ExpressCompany of(String code) {
        if (code == null) {
            return null;
        }
        for (ExpressCompany c : values()) {
            if (c.name().equalsIgnoreCase(code)) {
                return c;
            }
        }
        return null;
    }

    /** 未知 code（历史数据/前端乱传）时原样回显，不要吞成空 */
    public static String textOf(String code) {
        ExpressCompany c = of(code);
        return c == null ? (code == null ? "" : code) : c.text;
    }
}
