package com.mall.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.core.entity.Sku;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SkuMapper extends BaseMapper<Sku> {

    /**
     * 下单锁库存（防超卖的关键）：可售 = stock - locked_stock。
     * 条件更新在数据库层原子完成，返回 0 表示可售不足。
     */
    @Update("UPDATE t_sku SET locked_stock = locked_stock + #{qty}, update_time = NOW() " +
            "WHERE id = #{skuId} AND deleted = 0 AND stock - locked_stock >= #{qty}")
    int lockStock(@Param("skuId") Long skuId, @Param("qty") int qty);

    /** 取消订单 / 超时关单：释放锁定库存（总库存不动） */
    @Update("UPDATE t_sku SET locked_stock = GREATEST(locked_stock - #{qty}, 0), update_time = NOW() " +
            "WHERE id = #{skuId} AND deleted = 0")
    int releaseStock(@Param("skuId") Long skuId, @Param("qty") int qty);

    /** 支付成功：真正扣减总库存，同时释放锁定 */
    @Update("UPDATE t_sku SET stock = stock - #{qty}, locked_stock = GREATEST(locked_stock - #{qty}, 0), " +
            "update_time = NOW() WHERE id = #{skuId} AND deleted = 0 AND stock >= #{qty}")
    int deductStock(@Param("skuId") Long skuId, @Param("qty") int qty);
}
