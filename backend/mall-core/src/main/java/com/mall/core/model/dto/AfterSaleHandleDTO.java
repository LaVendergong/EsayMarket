package com.mall.core.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "商家处理售后")
public class AfterSaleHandleDTO {

    @Size(max = 200, message = "备注最多 200 字")
    @Schema(description = "处理备注（同意/拒绝都会展示给用户）")
    private String remark;
}
