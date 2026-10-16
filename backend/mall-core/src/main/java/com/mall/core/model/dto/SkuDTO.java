package com.mall.core.model.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class SkuDTO {

    /** 更新已有 SKU 时必传；新增时不传 */
    private Long id;

    @Size(max = 64, message = "长度不能超过 64")
    private String skuCode;

    /** 规格描述，不传按「默认」处理 */
    @Size(max = 255, message = "长度不能超过 255")
    private String spec;

    private String image;

    @NotNull(message = "SKU 价格不能为空")
    @DecimalMin(value = "0.00", message = "价格不能小于 0")
    private BigDecimal price;

    @NotNull(message = "SKU 库存不能为空")
    @Min(value = 0, message = "库存不能小于 0")
    private Integer stock;
}
