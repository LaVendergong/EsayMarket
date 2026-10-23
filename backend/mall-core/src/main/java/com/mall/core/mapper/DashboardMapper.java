package com.mall.core.mapper;

import com.mall.core.model.vo.LowStockVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 后台看板的聚合查询。
 *
 * <p>口径与 {@link ReportMapper} 保持一致：GMV 只算已支付状态（1,2,3,5），按 pay_time 归期。
 */
@Mapper
public interface DashboardMapper {

    @Select("SELECT IFNULL(SUM(pay_amount), 0) FROM t_order "
            + "WHERE deleted = 0 AND status IN (1,2,3,5) AND pay_time >= #{from}")
    BigDecimal gmvSince(@Param("from") LocalDateTime from);

    @Select("SELECT COUNT(*) FROM t_order "
            + "WHERE deleted = 0 AND status IN (1,2,3,5) AND pay_time >= #{from}")
    long ordersSince(@Param("from") LocalDateTime from);

    /** 待发货：实物订单、已支付 */
    @Select("SELECT COUNT(*) FROM t_order WHERE deleted = 0 AND status = 1 AND order_type = 1")
    long pendingShip();

    /** 待补发：虚拟订单已支付但没发放成功（卡密不足），停在状态 1 */
    @Select("SELECT COUNT(*) FROM t_order WHERE deleted = 0 AND status = 1 AND order_type = 2")
    long pendingGrant();

    @Select("SELECT COUNT(*) FROM t_after_sale WHERE deleted = 0 AND status = 0")
    long pendingAfterSale();

    @Select("SELECT COUNT(*) FROM t_sku s JOIN t_product p ON p.id = s.product_id "
            + "WHERE s.deleted = 0 AND p.deleted = 0 AND p.status = 1 "
            + "AND (s.stock - IFNULL(s.locked_stock, 0)) <= #{threshold}")
    long lowStockCount(@Param("threshold") int threshold);

    /**
     * 低库存明细。虚拟卡密商品 stock 天然为 0（等运营导入），也会命中这里 —— 这是想要的：
     * 看板上「卡密商品可售 0」就是在提醒去导入卡密。
     */
    @Select("SELECT s.id AS sku_id, s.product_id AS product_id, p.name AS product_name, s.spec AS spec, "
            + "s.stock AS stock, IFNULL(s.locked_stock, 0) AS locked_stock, "
            + "(s.stock - IFNULL(s.locked_stock, 0)) AS available "
            + "FROM t_sku s JOIN t_product p ON p.id = s.product_id "
            + "WHERE s.deleted = 0 AND p.deleted = 0 AND p.status = 1 "
            + "AND (s.stock - IFNULL(s.locked_stock, 0)) <= #{threshold} "
            + "ORDER BY available ASC, s.id LIMIT #{limit}")
    List<LowStockVO> lowStock(@Param("threshold") int threshold, @Param("limit") int limit);
}
