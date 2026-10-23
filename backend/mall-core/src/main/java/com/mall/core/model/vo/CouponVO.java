package com.mall.core.model.vo;

import com.mall.core.entity.Coupon;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Schema(description = "优惠券（领券中心/后台）")
public class CouponVO {

    private Long id;

    private String name;

    private BigDecimal threshold;

    private BigDecimal amount;

    private Integer total;

    private Integer received;

    private LocalDateTime validFrom;

    private LocalDateTime validTo;

    private Integer status;

    /** 当前用户是否已领取 */
    private Boolean receivedByMe;

    public static CouponVO from(Coupon c) {
        CouponVO vo = new CouponVO();
        vo.setId(c.getId());
        vo.setName(c.getName());
        vo.setThreshold(c.getThreshold());
        vo.setAmount(c.getAmount());
        vo.setTotal(c.getTotal());
        vo.setReceived(c.getReceived());
        vo.setValidFrom(c.getValidFrom());
        vo.setValidTo(c.getValidTo());
        vo.setStatus(c.getStatus());
        return vo;
    }
}