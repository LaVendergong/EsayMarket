package com.mall.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.core.entity.FreightTemplate;
import org.apache.ibatis.annotations.Update;

public interface FreightTemplateMapper extends BaseMapper<FreightTemplate> {

    /**
     * 后台整单更新，允许 {@code free_threshold} 写回 NULL。
     *
     * <p>{@code updateById} 会跳过 null 字段：把「满额包邮」改成「固定运费」后，
     * 库里还留着旧的包邮门槛，后台再打开又是「满 99 包邮」，看着像没保存。
     */
    @Update("UPDATE t_freight_template SET name = #{name}, type = #{type}, base_fee = #{baseFee}, "
            + "free_threshold = #{freeThreshold}, status = #{status}, update_time = NOW() "
            + "WHERE id = #{id} AND deleted = 0")
    int updateEditable(FreightTemplate template);
}
