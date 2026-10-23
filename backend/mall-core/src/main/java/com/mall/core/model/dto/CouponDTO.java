package com.mall.core.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Schema(description = "后台新增/修改优惠券")
public class CouponDTO {

    @NotBlank(message = "券名不能为空")
    @Size(max = 64, message = "长度不能超过 64")
    private String name;

    /** 使用门槛（按商品金额判断，不含运费） */
    @NotNull(message = "门槛不能为空")
    @DecimalMin(value = "0.00", message = "门槛不能小于 0")
    private BigDecimal threshold;

    @NotNull(message = "优惠金额不能为空")
    @DecimalMin(value = "0.01", message = "优惠金额必须大于 0")
    private BigDecimal amount;

    @NotNull(message = "发放总量不能为空")
    @Min(value = 0, message = "发放总量不能小于 0")
    private Integer total;

    private LocalDateTime validFrom;

    private LocalDateTime validTo;

    /** 1 可领 0 停发，不传默认 1 */
    private Integer status;
}
