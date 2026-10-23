package com.mall.core.model.vo;

import com.mall.core.enums.UserCouponStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Schema(description = "我的优惠券")
public class UserCouponVO {

    /** 用户券 id（下单时传这个，不是券模板 id） */
    private Long id;

    private Long couponId;

    private String name;

    private BigDecimal threshold;

    private BigDecimal amount;

    private Integer status;

    private String statusText;

    private Long orderId;

    private LocalDateTime validFrom;

    private LocalDateTime validTo;

    private LocalDateTime receiveTime;

    private LocalDateTime useTime;

    /** 当前订单金额下是否可用（结算页用它置灰） */
    private Boolean usable;

    public static UserCouponVO of(Long id, Long couponId, String name, BigDecimal threshold,
                                  BigDecimal amount, Integer status, Long orderId,
                                  LocalDateTime validFrom, LocalDateTime validTo,
                                  LocalDateTime receiveTime, LocalDateTime useTime) {
        UserCouponVO vo = new UserCouponVO();
        vo.setId(id);
        vo.setCouponId(couponId);
        vo.setName(name);
        vo.setThreshold(threshold);
        vo.setAmount(amount);
        vo.setStatus(status);
        vo.setStatusText(UserCouponStatus.textOf(status));
        vo.setOrderId(orderId);
        vo.setValidFrom(validFrom);
        vo.setValidTo(validTo);
        vo.setReceiveTime(receiveTime);
        vo.setUseTime(useTime);
        return vo;
    }
}