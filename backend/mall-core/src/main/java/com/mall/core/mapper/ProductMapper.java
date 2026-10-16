package com.mall.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.core.entity.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProductMapper extends BaseMapper<Product> {

    /** 支付成功后累加销量（商品列表 sales_desc / 综合排序依赖它） */
    @Update("UPDATE t_product SET sales = sales + #{qty}, update_time = NOW() " +
            "WHERE id = #{productId} AND deleted = 0")
    int incrementSales(@Param("productId") Long productId, @Param("qty") int qty);
}
