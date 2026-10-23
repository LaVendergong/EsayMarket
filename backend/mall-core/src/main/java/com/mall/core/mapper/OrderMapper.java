package com.mall.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.core.entity.Order;
import com.mall.core.model.vo.OrderExportRowVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    /**
     * 导出用查询（不做分页，靠 limit 兜底）。
     *
     * <p>商品名用 GROUP_CONCAT 拼成一行：财务对账关心「这一单买了什么」，
     * 拆成多行在 Excel 里反而不好筛。分隔符用 ` | ` 而不是逗号 —— 逗号会和 CSV 字段分隔冲突。
     */
    @Select("<script>"
            + "SELECT o.id, o.order_no, o.create_time, o.pay_time, o.status, o.user_id, "
            + "o.total_amount, o.freight_amount, o.discount_amount, o.pay_amount, o.order_type, "
            + "o.ship_company, o.ship_no, o.receiver, "
            + "(SELECT GROUP_CONCAT(oi.product_name SEPARATOR ' | ') FROM t_order_item oi WHERE oi.order_id = o.id) AS items "
            + "FROM t_order o WHERE o.deleted = 0 "
            + "<if test='status != null'>AND o.status = #{status} </if>"
            + "<if test='keyword != null and keyword != \"\"'>"
            + "AND (o.order_no LIKE CONCAT('%', #{keyword}, '%') OR o.receiver LIKE CONCAT('%', #{keyword}, '%')) "
            + "</if>"
            + "ORDER BY o.id DESC LIMIT #{limit}"
            + "</script>")
    List<OrderExportRowVO> listForExport(@Param("status") Integer status,
                                         @Param("keyword") String keyword,
                                         @Param("limit") int limit);
}
