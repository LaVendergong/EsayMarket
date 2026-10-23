package com.mall.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mall.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_product")
public class Product extends BaseEntity {

    private Long categoryId;

    private String name;

    private String subTitle;

    private String mainImage;

    /** 图集，多个 URL 用英文逗号分隔 */
    private String images;

    private String detail;

    /** 展示价；多规格商品由后台填写（一般等于最低 SKU 价） */
    private BigDecimal price;

    /** 0-下架 1-上架 */
    private Integer status;

    /** 运费模板 id；为空表示包邮（历史商品不会因为上运费而突然加价） */
    private Long freightTemplateId;

    /** 1 实物 2 虚拟（虚拟不收运费、不要地址、支付即发放） */
    private Integer productType;

    /** 虚拟商品的发放方式：1 卡密 2 外链 */
    private Integer deliveryType;

    /** 外链类商品的下载地址 */
    private String deliveryUrl;

    /** 平均分（只统计展示中的评价，评价增删/隐藏后重算） */
    private BigDecimal ratingAvg;

    /** 评价条数 */
    private Integer reviewCount;

    private Integer sales;
}
