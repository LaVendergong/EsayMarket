package com.mall.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.core.entity.UserCoupon;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface UserCouponMapper extends BaseMapper<UserCoupon> {

    /**
     * 批量把「未使用」置为「已过期」。
     *
     * <p>写在这里而不是 service 里拼 lambdaUpdate：一是这条 SQL 带子查询、可读性差，
     * 二是纯单测里没有 MyBatis-Plus 的实体元数据缓存，构造 lambda wrapper 会直接抛异常。
     */
    @Update("UPDATE t_user_coupon SET status = 2 "
            + "WHERE status = 0 AND deleted = 0 AND coupon_id IN "
            + "(SELECT id FROM t_coupon WHERE deleted = 0 AND valid_to IS NOT NULL AND valid_to < NOW())")
    int expireByOutdatedTemplate();

    /** 按用户券 id 精准纠正（查券时兜底用） */
    @Update("<script>UPDATE t_user_coupon SET status = 2 WHERE status = 0 AND deleted = 0 AND id IN "
            + "<foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int markExpired(@Param("ids") List<Long> ids);
}
