package com.mall.core.service;

import com.mall.core.model.dto.FreightTemplateDTO;
import com.mall.core.model.vo.FreightTemplateVO;

import java.util.List;

/** 运费模板（后台维护，供商品绑定） */
public interface FreightService {

    /** status 为 null 时返回全部 */
    List<FreightTemplateVO> list(Integer status);

    Long create(FreightTemplateDTO dto);

    void update(Long id, FreightTemplateDTO dto);

    /** 启用/停用 status=1|0。停用的模板在计价时按包邮处理 */
    void updateStatus(Long id, Integer status);

    /** 删除。还有商品绑定时不许删，避免商品突然变成"模板不存在" */
    void delete(Long id);
}
