package com.mall.core.model.vo;

import com.mall.core.entity.Product;
import com.mall.core.enums.DeliveryType;
import com.mall.core.enums.ProductType;
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
    /** 运费模板 id；null = 包邮 */
    private Long freightTemplateId;
    /** 运费说明，直接给前台展示（如「包邮」「运费 ¥8.00」「满 ¥99.00 包邮」） */
    private String freightText;
    /** 1 实物 2 虚拟 */
    private Integer productType;
    private String productTypeText;
    /** 虚拟：1 卡密 2 外链 */
    private Integer deliveryType;
    private String deliveryTypeText;
    /** 外链地址（前台在发放前不展示，避免没付款就拿到链接） */
    private String deliveryUrl;
    /** 平均分（0 = 暂无评价） */
    private BigDecimal ratingAvg;
    private Integer reviewCount;
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
        // 虚拟商品不展示运费模板（updateById 不会把老值清成 null，回显时按类型屏蔽，避免运营看到「虚拟商品绑了运费」）
        vo.setFreightTemplateId(ProductType.of(p.getProductType()) == ProductType.VIRTUAL
                ? null : p.getFreightTemplateId());
        vo.setProductType(ProductType.of(p.getProductType()).getCode());
        vo.setProductTypeText(ProductType.textOf(p.getProductType()));
        vo.setDeliveryType(p.getDeliveryType());
        vo.setDeliveryTypeText(DeliveryType.textOf(p.getDeliveryType()));
        vo.setRatingAvg(p.getRatingAvg());
        vo.setReviewCount(p.getReviewCount());
        vo.setSkus(skus);
        return vo;
    }
}
