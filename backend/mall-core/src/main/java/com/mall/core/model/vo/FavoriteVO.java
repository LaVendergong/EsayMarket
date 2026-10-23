package com.mall.core.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Schema(description = "我的收藏")
public class FavoriteVO {

    private Long id;

    private Long productId;

    private String productName;

    private String subTitle;

    private String mainImage;

    private BigDecimal price;

    /** 商品当前状态：0 已下架（列表里要提示，不能点进去报错） */
    private Integer status;

    /** 当前可售库存（SKU 合计） */
    private Integer stock;

    private LocalDateTime favoriteTime;
}
