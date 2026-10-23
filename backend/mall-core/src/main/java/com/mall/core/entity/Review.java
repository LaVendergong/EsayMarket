package com.mall.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mall.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 商品评价。
 *
 * <p>`orderItemId` 上有唯一键：一个订单项只能评一次，并发重复提交会撞键而不是插入两条。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_review")
public class Review extends BaseEntity {

    private Long orderId;

    private Long orderItemId;

    private Long userId;

    private Long productId;

    private Long skuId;

    /** 1-5 星 */
    private Integer rating;

    private String content;

    /** 1 匿名 */
    private Integer anonymous;

    /** 晒图 URL，逗号分隔，最多 3 张（图片本身存磁盘/对象存储，这里只存地址） */
    private String images;

    /** 1 展示 0 后台隐藏 */
    private Integer status;

    /** 1 = 该订单已退款：评价仍展示，但不计入商品评分 */
    private Integer refunded;
}
