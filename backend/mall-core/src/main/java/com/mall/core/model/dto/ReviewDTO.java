package com.mall.core.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "提交商品评价")
public class ReviewDTO {

    /** 评哪个订单项：服务端据此反查订单和商品，前端不传 productId */
    @NotNull(message = "订单项不能为空")
    private Long orderItemId;

    @NotNull(message = "请打分")
    @Min(value = 1, message = "评分为 1-5 星")
    @Max(value = 5, message = "评分为 1-5 星")
    private Integer rating;

    @Size(max = 500, message = "评价内容不能超过 500 字")
    private String content;

    /** 1 匿名 */
    private Integer anonymous;

    /** 晒图 URL（先调 /portal/upload/image 上传拿到地址），最多 3 张 */
    @Size(max = 3, message = "最多上传 3 张图片")
    private List<String> images;
}
