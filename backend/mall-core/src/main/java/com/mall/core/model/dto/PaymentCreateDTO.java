package com.mall.core.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "创建支付单")
public class PaymentCreateDTO {

    @NotNull(message = "订单不能为空")
    @Schema(description = "订单 id")
    private Long orderId;

    @NotBlank(message = "请选择支付方式")
    @Schema(description = "支付渠道：MOCK / ALIPAY_WAP / WECHAT_NATIVE")
    private String channel;
}
