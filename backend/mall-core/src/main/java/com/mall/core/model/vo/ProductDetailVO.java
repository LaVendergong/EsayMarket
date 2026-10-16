package com.mall.core.model.vo;

import com.mall.core.entity.Product;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品详情（含 SKU 列表与图集）
 */
@Data
public class ProductDetailVO {

    private Long id;
    private Long categoryId;
    private String categoryName;
    private String name;
    private String subTitle;
    private String mainImage;
    private List<String> images;
    private String detail;
    private BigDecimal price;
    private Integer status;
    private Integer sales;
    private List<SkuVO> skus;

    public static ProductDetailVO from(Product p, String categoryName, List<String> images, List<SkuVO> skus) {
        ProductDetailVO vo = new ProductDetailVO();
        vo.setId(p.getId());
        vo.setCategoryId(p.getCategoryId());
        vo.setCategoryName(categoryName);
        vo.setName(p.getName());
        vo.setSubTitle(p.getSubTitle());
        vo.setMainImage(p.getMainImage());
        vo.setImages(images);
        vo.setDetail(p.getDetail());
        vo.setPrice(p.getPrice());
        vo.setStatus(p.getStatus());
        vo.setSales(p.getSales());
        vo.setSkus(skus);
        return vo;
    }
}
