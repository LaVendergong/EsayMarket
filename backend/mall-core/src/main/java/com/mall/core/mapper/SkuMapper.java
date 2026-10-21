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

    /**
     * 退货退款时把库存加回去（和 deductStock 对称）。
     *
     * <p>只在 {@code AfterSaleType.RETURN_REFUND} 上调：仅退款没有货退回仓库，
     * 加库存会让账面库存虚高、进而超卖。
     */
    @Update("UPDATE t_sku SET stock = stock + #{qty}, update_time = NOW() WHERE id = #{skuId} AND deleted = 0")
    int restoreStock(@Param("skuId") Long skuId, @Param("qty") int qty);
}
