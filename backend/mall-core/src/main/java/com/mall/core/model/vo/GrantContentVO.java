package com.mall.core.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 查看凭证明文的结果（每次调用都会让 viewCount +1，售后据此判断能不能退） */
@Data
@Schema(description = "凭证内容")
public class GrantContentVO {

    /** 1 卡密 2 外链 */
    private Integer deliveryType;

    /**
     * 明文内容：
     * 卡密类为 {@code 卡号|卡密}（前端按 | 拆两行显示），
     * 外链类为完整 URL。
     */
    private String content;

    /** 累计查看次数（含本次） */
    private Integer viewCount;
}
