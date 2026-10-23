package com.mall.core.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 后台：某张券的领取明细（谁领的、用没用、用在哪一单） */
@Data
@Schema(description = "优惠券领取记录")
public class CouponRecordVO {

    /** 用户券 id */
    private Long id;

    private Long userId;

    private String username;

    private String nickname;

    /** 0 未使用 1 已使用 2 已过期 */
    private Integer status;

    private String statusText;

    private LocalDateTime receiveTime;

    private LocalDateTime useTime;

    private Long orderId;
}
