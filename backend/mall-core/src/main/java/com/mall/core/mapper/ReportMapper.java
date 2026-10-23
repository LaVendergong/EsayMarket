package com.mall.core.mapper;

import com.mall.core.model.vo.DailyStatVO;
import com.mall.core.model.vo.TopProductVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 销售报表的聚合查询。
 *
 * <p>口径说明（很重要，改之前先看清楚）：
 * <ul>
 *   <li><b>GMV</b> 只统计「已支付 / 已发货 / 已完成 / 售后中」四个状态，按 {@code pay_time} 归期。
 *       待支付不算（钱还没到），已关闭不算，已退款不算 —— 否则退款单会虚增 GMV。</li>
 *   <li><b>退款额</b> 取售后单 {@code APPROVED(1)} 且 {@code refund_time} 在区间内的 {@code refund_amount}。</li>
 *   <li><b>热销</b> 按件数排，`t_order_item.pay_amount` 是分摊优惠后的实付，老数据可能为 NULL 用 IFNULL 兜。</li>
 * </ul>
 */
@Mapper
public interface ReportMapper {

    // 已支付的订单状态：1 已支付 2 已发货 3 已完成 5 售后中（4 已关闭 / 6 已退款都排除）

    @Select("SELECT IFNULL(SUM(pay_amount), 0) FROM t_order "
            + "WHERE deleted = 0 AND status IN (1,2,3,5) AND pay_time >= #{from} AND pay_time < #{to}")
    BigDecimal gmv(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Select("SELECT COUNT(*) FROM t_order "
            + "WHERE deleted = 0 AND status IN (1,2,3,5) AND pay_time >= #{from} AND pay_time < #{to}")
    long orderCount(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Select("SELECT COUNT(DISTINCT user_id) FROM t_order "
            + "WHERE deleted = 0 AND status IN (1,2,3,5) AND pay_time >= #{from} AND pay_time < #{to}")
    long buyerCount(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Select("SELECT IFNULL(SUM(refund_amount), 0) FROM t_after_sale "
            + "WHERE deleted = 0 AND status = 1 AND refund_time >= #{from} AND refund_time < #{to}")
    BigDecimal refundAmount(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Select("SELECT COUNT(*) FROM t_user WHERE deleted = 0 AND create_time >= #{from} AND create_time < #{to}")
    long newUsers(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Select("SELECT COUNT(*) FROM t_user_coupon WHERE deleted = 0 "
            + "AND receive_time >= #{from} AND receive_time < #{to}")
    long couponReceived(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 核销 = 真的用在了订单上（use_time 非空），领了没用/过期的不算 */
    @Select("SELECT COUNT(*) FROM t_user_coupon WHERE deleted = 0 "
            + "AND use_time >= #{from} AND use_time < #{to}")
    long couponUsed(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Select("SELECT DATE(pay_time) AS stat_date, COUNT(*) AS order_count, IFNULL(SUM(pay_amount), 0) AS gmv "
            + "FROM t_order WHERE deleted = 0 AND status IN (1,2,3,5) "
            + "AND pay_time >= #{from} AND pay_time < #{to} "
            + "GROUP BY DATE(pay_time) ORDER BY stat_date")
    List<DailyStatVO> daily(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Select("SELECT oi.product_id AS product_id, oi.product_name AS product_name, "
            + "SUM(oi.quantity) AS quantity, IFNULL(SUM(oi.pay_amount), 0) AS amount "
            + "FROM t_order_item oi JOIN t_order o ON o.id = oi.order_id "
            + "WHERE o.deleted = 0 AND o.status IN (1,2,3,5) "
            + "AND o.pay_time >= #{from} AND o.pay_time < #{to} "
            + "GROUP BY oi.product_id, oi.product_name ORDER BY quantity DESC LIMIT #{limit}")
    List<TopProductVO> topProducts(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                   @Param("limit") int limit);
}
