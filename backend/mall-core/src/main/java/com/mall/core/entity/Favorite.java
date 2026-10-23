package com.mall.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mall.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 商品收藏。
 *
 * <p>唯一键 (user_id, product_id)：取消收藏是逻辑删除，再次收藏时把 deleted 置回 0，
 * 不能直接 insert —— 会撞唯一键。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_favorite")
public class Favorite extends BaseEntity {

    private Long userId;

    private Long productId;
}
