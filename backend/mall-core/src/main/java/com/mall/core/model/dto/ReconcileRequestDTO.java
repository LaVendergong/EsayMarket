package com.mall.core.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
@Schema(description = "对账请求：上传某渠道某天的账单内容")
public class ReconcileRequestDTO {

    @NotBlank(message = "请选择渠道")
    @Schema(description = "MOCK / ALIPAY_WAP / WECHAT_NATIVE")
    private String channel;

    @NotNull(message = "请选择账单日期")
    @Schema(description = "账单日期 yyyy-MM-dd")
    private LocalDate billDate;

    @NotBlank(message = "账单内容不能为空")
    @Schema(description = "账单文件内容（CSV 原文）")
    private String content;
}
