package com.mall.core.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
@Schema(description = "发货：快递公司 + 运单号")
public class ShipDTO {

    /** 枚举 code：SF/ZTO/YTO/YD/JD/EMS/OTHER */
    @NotBlank(message = "请选择快递公司")
    private String company;

    @NotBlank(message = "请填写运单号")
    @Pattern(regexp = "[A-Za-z0-9-]{4,64}", message = "运单号只能是 4-64 位字母、数字或短横线")
    private String shipNo;
}
