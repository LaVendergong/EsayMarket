package com.mall.core.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "申请售后")
public class AfterSaleApplyDTO {

    @NotNull(message = "订单不能为空")
    @Schema(description = "订单 id")
    private Long orderId;

    @NotNull(message = "请选择售后类型")
    @Schema(description = "1-仅退款 2-退货退款")
    private Integer type;

    @NotBlank(message = "请选择售后原因")
    @Size(max = 64, message = "原因最多 64 字")
    @Schema(description = "售后原因")
    private String reason;

    @Size(max = 500, message = "补充说明最多 500 字")
    @Schema(description = "补充说明")
    private String description;
}
