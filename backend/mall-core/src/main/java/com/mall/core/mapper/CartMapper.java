package com.mall.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.core.entity.Cart;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 购物车是临时数据，删除走物理删除。
 * 原因：t_cart 的唯一键是 (user_id, sku_id, deleted)，逻辑删除会留下 deleted=1 的墓碑行，
 * 同一 (user, sku) 第二次被逻辑删除时会撞唯一键。
 */
@Mapper
public interface CartMapper extends BaseMapper<Cart> {

    @Delete("DELETE FROM t_cart WHERE id = #{id} AND user_id = #{userId}")
    int deleteOwned(@Param("id") Long id, @Param("userId") Long userId);

    @Delete("DELETE FROM t_cart WHERE user_id = #{userId} AND checked = 1")
    int deleteChecked(@Param("userId") Long userId);
}
