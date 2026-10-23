package com.mall.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.core.entity.Coupon;
import org.apache.ibatis.annotations.Update;

public interface CouponMapper extends BaseMapper<Coupon> {

    /**
     * 后台整单更新。
     *
     * <p>为什么不直接用 {@code updateById}：它默认跳过 null 字段，于是「把失效时间改回长期有效」
     * 这个操作永远不生效（界面清空、保存、刷新还是旧值）。这里显式列出字段，允许写回 NULL。
     */
    @Update("UPDATE t_coupon SET name = #{name}, threshold = #{threshold}, amount = #{amount}, "
            + "total = #{total}, valid_from = #{validFrom}, valid_to = #{validTo}, status = #{status}, "
            + "update_time = NOW() WHERE id = #{id} AND deleted = 0")
    int updateEditable(Coupon coupon);
}
