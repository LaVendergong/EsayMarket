package com.mall.core.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class ProductDTO {

    @NotNull(message = "分类不能为空")
    private Long categoryId;

    @NotBlank(message = "商品名称不能为空")
    @Size(max = 128, message = "长度不能超过 128")
    private String name;

    @Size(max = 255, message = "长度不能超过 255")
    private String subTitle;

    @Size(max = 255, message = "长度不能超过 255")
    private String mainImage;

    /** 图集 URL 列表，落库时用英文逗号拼接 */
    private List<String> images;

    private String detail;

    @NotNull(message = "商品价格不能为空")
    @DecimalMin(value = "0.00", message = "价格不能小于 0")
    private BigDecimal price;

    /** 0-下架 1-上架，不传默认 1 */
    private Integer status;

    @NotEmpty(message = "至少需要一个 SKU")
    @Valid
    private List<SkuDTO> skus;
}
