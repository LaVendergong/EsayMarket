package com.mall.core.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "商品评价")
public class ReviewVO {

    private Long id;

    private Long orderId;

    private Long orderItemId;

    private Long productId;

    private Long skuId;

    /** 规格快照（同一商品不同规格的评价要能区分） */
    private String spec;

    /** 后台列表用：商品名快照 */
    private String productName;

    /** 1-5 */
    private Integer rating;

    private String content;

    /** 晒图 URL 列表（最多 3 张） */
    private List<String> images;

    /** 展示用的名字：匿名评价固定为「匿名用户」 */
    private String userLabel;

    private Integer anonymous;

    /** 1 展示 0 隐藏 */
    private Integer status;

    /** 1 = 该订单已退款，评价仍展示但不计入商品评分 */
    private Integer refunded;

    private LocalDateTime createTime;
}
