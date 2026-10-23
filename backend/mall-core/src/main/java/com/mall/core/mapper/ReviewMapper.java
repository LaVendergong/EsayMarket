package com.mall.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.core.entity.Review;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface ReviewMapper extends BaseMapper<Review> {

    /**
     * 重算商品评分并写回商品表。
     *
     * <p>只统计 status=1 的评价：后台隐藏一条差评后，商品上的分数必须跟着降/升，
     * 否则列表页的 4.9 分和详情页的评价内容对不上。
     */
    @Update("UPDATE t_product p SET "
            + "p.review_count = (SELECT COUNT(*) FROM t_review r "
            + "  WHERE r.product_id = p.id AND r.status = 1 AND r.refunded = 0 AND r.deleted = 0), "
            + "p.rating_avg = IFNULL((SELECT ROUND(AVG(r.rating), 1) FROM t_review r "
            + "  WHERE r.product_id = p.id AND r.status = 1 AND r.refunded = 0 AND r.deleted = 0), 0) "
            + "WHERE p.id = #{productId}")
    int refreshProductRating(@Param("productId") Long productId);

    /**
     * 订单退款：评价保留（用户确实写过），但打上 refunded 标记，不再计入商品评分。
     * 直接隐藏评价会让用户觉得"我的评价被删了"。
     */
    @Update("UPDATE t_review SET refunded = 1, update_time = NOW() WHERE order_id = #{orderId} AND deleted = 0")
    int markRefundedByOrder(@Param("orderId") Long orderId);
}
