package com.mall.core.model.vo;

import com.mall.core.enums.DeliveryType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 发放凭证（列表用）。
 *
 * <p>**故意不返回内容**：卡密一旦出现在列表接口的响应里，就会进日志、进浏览器缓存、进抓包。
 * 要看明文必须单独调 reveal（会校验归属并累加 viewCount）。
 */
@Data
@Schema(description = "虚拟商品凭证")
public class DigitalGrantVO {

    private Long id;

    private Long orderId;

    private Long orderItemId;

    private Long productId;

    private Long skuId;

    /** 商品名/规格/图片：列表页直接展示，省一次商品查询 */
    private String productName;

    private String spec;

    private String productImage;

    /** 1 卡密 2 外链 */
    private Integer deliveryType;

    private String deliveryTypeText;

    /** 1 已发放 2 已作废 9 发放失败 */
    private Integer status;

    private String statusText;

    private Integer viewCount;

    private LocalDateTime grantTime;

    public static DigitalGrantVO of(Long id, Long orderId, Long orderItemId, Long productId, Long skuId,
                                    Integer deliveryType, Integer status, Integer viewCount,
                                    LocalDateTime grantTime) {
        DigitalGrantVO vo = new DigitalGrantVO();
        vo.setId(id);
        vo.setOrderId(orderId);
        vo.setOrderItemId(orderItemId);
        vo.setProductId(productId);
        vo.setSkuId(skuId);
        vo.setDeliveryType(deliveryType);
        vo.setDeliveryTypeText(DeliveryType.textOf(deliveryType));
        vo.setStatus(status);
        vo.setStatusText(statusText(status));
        vo.setViewCount(viewCount);
        vo.setGrantTime(grantTime);
        return vo;
    }

    public static String statusText(Integer status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case 1 -> "已发放";
            case 2 -> "已作废";
            case 9 -> "发放失败";
            default -> "未知";
        };
    }
}
