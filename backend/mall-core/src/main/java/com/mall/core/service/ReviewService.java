package com.mall.core.service;

import com.mall.common.result.PageResult;
import com.mall.core.model.dto.ReviewDTO;
import com.mall.core.model.vo.ReviewVO;

import java.util.List;

/** 商品评价：只有已完成的订单能评，一个订单项一次 */
public interface ReviewService {

    void submit(Long userId, ReviewDTO dto);

    /** 商品详情页的评价列表（只返回展示中的） */
    PageResult<ReviewVO> pageForProduct(Long productId, long pageNum, long pageSize);

    /** 该订单里已经评过的订单项 id：前端据此把「评价」换成「已评价」 */
    List<Long> reviewedItemIds(Long userId, Long orderId);

    // ------------------------------------------------------------------ 后台

    PageResult<ReviewVO> pageForAdmin(long pageNum, long pageSize, Long productId, Integer status);

    /** 0 隐藏 / 1 恢复；都会重算商品评分 */
    void updateStatus(Long id, Integer status);

    /**
     * 订单退款：相关评价打上 refunded 标记（保留展示），并从商品评分里剔除。
     *
     * <p>不直接隐藏评价 —— 用户确实写过，突然消失会让人觉得"评价被删了"。
     */
    void markRefundedByOrder(Long orderId);
}
