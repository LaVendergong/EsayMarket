package com.mall.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.core.entity.DigitalGrant;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface DigitalGrantMapper extends BaseMapper<DigitalGrant> {

    /** 查看次数要自增，不能"读出来 +1 再写回"（并发下会丢计数） */
    @Update("UPDATE t_digital_grant SET view_count = view_count + 1 WHERE id = #{id} AND deleted = 0")
    int incrViewCount(@Param("id") Long id);

    /** 退款/售后同意后整单作废 */
    @Update("UPDATE t_digital_grant SET status = 2 WHERE order_id = #{orderId} AND deleted = 0")
    int revokeByOrder(@Param("orderId") Long orderId);
}
