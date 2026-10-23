package com.mall.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.core.entity.CardKey;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface CardKeyMapper extends BaseMapper<CardKey> {

    /**
     * 锁定一张可用卡（当前读 + 行锁）。
     *
     * <p>为什么不是一条 {@code UPDATE ... ORDER BY id LIMIT 1}：那样更新完拿不到是哪一条，
     * 还得再查一次且可能查错。先 FOR UPDATE 选中 id，再按 id 更新，两步都在发放事务里，并发安全。
     */
    @Select("SELECT id FROM t_card_key WHERE sku_id = #{skuId} AND status = 0 AND deleted = 0 "
            + "ORDER BY id LIMIT 1 FOR UPDATE")
    Long lockOne(@Param("skuId") Long skuId);

    @Update("UPDATE t_card_key SET status = 1, order_id = #{orderId}, order_item_id = #{orderItemId}, "
            + "sold_time = NOW() WHERE id = #{id}")
    int markSold(@Param("id") Long id, @Param("orderId") Long orderId, @Param("orderItemId") Long orderItemId);

    @Select("SELECT COUNT(*) FROM t_card_key WHERE sku_id = #{skuId} AND status = 0 AND deleted = 0")
    long countAvailable(@Param("skuId") Long skuId);

    /**
     * 对账：可售库存（stock - locked）与池子里可用卡数不一致的 SKU 数量。
     *
     * <p>两者本该永远相等（导入加库存、下单锁库存、支付扣库存并从池子取卡）。
     * 不相等说明有人绕过接口改库，或发放时条件 UPDATE 没命中 —— 继续卖会发出空卡。
     */
    @Select("SELECT COUNT(*) FROM ("
            + "SELECT s.id FROM t_sku s JOIN t_product p ON p.id = s.product_id "
            + "WHERE p.deleted = 0 AND s.deleted = 0 AND p.product_type = 2 AND p.delivery_type = 1 "
            + "AND (s.stock - s.locked_stock) <> "
            + "(SELECT COUNT(*) FROM t_card_key c WHERE c.sku_id = s.id AND c.status = 0 AND c.deleted = 0)"
            + ") t")
    long countMismatchedSkus();

    /** 退款时作废该订单项发出的卡：不回池，避免同一张卡被卖第二次 */
    @Update("<script>UPDATE t_card_key SET status = 2 WHERE deleted = 0 AND order_item_id IN "
            + "<foreach item='id' collection='itemIds' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int revokeByOrderItems(@Param("itemIds") List<Long> itemIds);
}
