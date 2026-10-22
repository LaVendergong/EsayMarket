package com.mall.core.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "处理对账差异")
public class ReconcileHandleDTO {

    /**
     * CONFIRM（已核实并处理）/ IGNORE（忽略）
     *
     * <p>刻意不支持"一键补记/一键重退"：那是不可逆的资金动作，必须走
     * 「同步支付」「查询退款」接口并人工确认。
     */
    @NotBlank(message = "请选择处理动作")
    @Schema(description = "CONFIRM / IGNORE")
    private String action;

    @Size(max = 200, message = "处理说明最多 200 字")
    @Schema(description = "处理说明（会记入审计）")
    private String remark;
}
