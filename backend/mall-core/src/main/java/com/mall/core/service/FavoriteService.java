package com.mall.core.service;

import com.mall.common.result.PageResult;
import com.mall.core.model.vo.FavoriteVO;

/** 商品收藏 */
public interface FavoriteService {

    /** 收藏/取消收藏，返回操作后的状态：true=已收藏 */
    boolean toggle(Long userId, Long productId);

    /** 我的收藏（分页，下架的也返回，前端标灰） */
    PageResult<FavoriteVO> page(Long userId, long pageNum, long pageSize);

    boolean isFavorite(Long userId, Long productId);
}
