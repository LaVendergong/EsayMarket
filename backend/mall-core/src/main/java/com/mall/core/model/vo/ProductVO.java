package com.mall.core.model.vo;

import com.mall.core.entity.Product;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 商品列表项（不含 SKU 明细）
 */
@Data
public class ProductVO {

    private Long id;
    private Long categoryId;
    private String categoryName;
    private String name;
    private String subTitle;
    private String mainImage;
    private BigDecimal price;
    private Integer status;
    private Integer sales;

    /** 该商品全部 SKU 的可售库存合计 */
    private Integer stock;

    public static ProductVO from(Product p) {
        ProductVO vo = new ProductVO();
        vo.setId(p.getId());
        vo.setCategoryId(p.getCategoryId());
        vo.setName(p.getName());
        vo.setSubTitle(p.getSubTitle());
        vo.setMainImage(p.getMainImage());
        vo.setPrice(p.getPrice());
        vo.setStatus(p.getStatus());
        vo.setSales(p.getSales());
        return vo;
    }
}
