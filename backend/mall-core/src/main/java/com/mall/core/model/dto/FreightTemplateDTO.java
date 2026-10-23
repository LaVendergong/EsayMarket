package com.mall.core.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "后台新增/修改运费模板")
public class FreightTemplateDTO {

    @NotBlank(message = "模板名不能为空")
    @Size(max = 64, message = "长度不能超过 64")
    private String name;

    /** FIXED 固定运费 / FREE_OVER 满额包邮 */
    @NotBlank(message = "类型不能为空")
    private String type;

    @DecimalMin(value = "0.00", message = "运费不能小于 0")
    private BigDecimal baseFee;

    /** FREE_OVER 时有效：满多少包邮 */
    @DecimalMin(value = "0.00", message = "包邮门槛不能小于 0")
    private BigDecimal freeThreshold;

    /** 1 启用 0 停用，不传默认 1 */
    private Integer status;
}
