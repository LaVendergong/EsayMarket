package com.mall.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.core.entity.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ProductMapper extends BaseMapper<Product> {

    /**
     * 搜索联想：按分类名匹配（分类是用户最先想到的一层）。
     *
     * <p>用前缀匹配（{@code kw%}）而不是包含匹配：分类名很短，包含匹配会把「机」这种单字
     * 命中一堆分类，联想反而没用。商品名那边同理用前缀 + 销量兜底。
     */
    @Select("SELECT name FROM t_category WHERE deleted = 0 AND status = 1 AND name LIKE #{like} "
            + "ORDER BY sort, id LIMIT #{limit}")
    List<String> suggestCategories(@Param("like") String like, @Param("limit") int limit);

    /** 搜索联想：按商品名匹配，销量高的排前面（用户更可能想搜热卖的） */
    @Select("SELECT name FROM t_product WHERE deleted = 0 AND status = 1 AND name LIKE #{like} "
            + "ORDER BY sales DESC, id DESC LIMIT #{limit}")
    List<String> suggestProducts(@Param("like") String like, @Param("limit") int limit);

    /** 支付成功后累加销量（商品列表 sales_desc / 综合排序依赖它） */
    @Update("UPDATE t_product SET sales = sales + #{qty}, update_time = NOW() " +
            "WHERE id = #{productId} AND deleted = 0")
    int incrementSales(@Param("productId") Long productId, @Param("qty") int qty);

    /**
     * 退款成功后回退销量。
     *
     * <p>用 GREATEST 兜底到 0：历史数据里销量可能小于退款件数（比如早期没记销量），
     * 不能让一次退款把销量干成负数。
     */
    @Update("UPDATE t_product SET sales = GREATEST(sales - #{qty}, 0), update_time = NOW() "
            + "WHERE id = #{productId} AND deleted = 0")
    int decrementSales(@Param("productId") Long productId, @Param("qty") int qty);
}
