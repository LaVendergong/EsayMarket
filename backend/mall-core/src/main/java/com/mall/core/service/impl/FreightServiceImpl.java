package com.mall.core.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.common.exception.BusinessException;
import com.mall.common.result.ResultCode;
import com.mall.core.entity.FreightTemplate;
import com.mall.core.entity.Product;
import com.mall.core.enums.FreightType;
import com.mall.core.mapper.FreightTemplateMapper;
import com.mall.core.mapper.ProductMapper;
import com.mall.core.model.dto.FreightTemplateDTO;
import com.mall.core.model.vo.FreightTemplateVO;
import com.mall.core.service.FreightService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class FreightServiceImpl implements FreightService {

    private final FreightTemplateMapper freightTemplateMapper;
    private final ProductMapper productMapper;

    @Override
    public List<FreightTemplateVO> list(Integer status) {
        return freightTemplateMapper.selectList(Wrappers.<FreightTemplate>lambdaQuery()
                        .eq(status != null, FreightTemplate::getStatus, status)
                        .orderByDesc(FreightTemplate::getId))
                .stream().map(FreightTemplateVO::from).toList();
    }

    @Override
    public Long create(FreightTemplateDTO dto) {
        FreightTemplate t = new FreightTemplate();
        apply(t, dto);
        freightTemplateMapper.insert(t);
        return t.getId();
    }

    @Override
    public void update(Long id, FreightTemplateDTO dto) {
        FreightTemplate t = requireTemplate(id);
        apply(t, dto);
        // 用 updateEditable：固定运费要把 free_threshold 写回 NULL，updateById 会跳过 null
        freightTemplateMapper.updateEditable(t);
    }

    @Override
    public void updateStatus(Long id, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "status 只能是 0 或 1");
        }
        requireTemplate(id);
        FreightTemplate upd = new FreightTemplate();
        upd.setId(id);
        upd.setStatus(status);
        freightTemplateMapper.updateById(upd);
    }

    @Override
    public void delete(Long id) {
        requireTemplate(id);
        Long bound = productMapper.selectCount(Wrappers.<Product>lambdaQuery()
                .eq(Product::getFreightTemplateId, id));
        if (bound != null && bound > 0) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED,
                    "还有 " + bound + " 个商品在用这个模板，请先改绑");
        }
        freightTemplateMapper.deleteById(id);
    }

    private FreightTemplate requireTemplate(Long id) {
        FreightTemplate t = freightTemplateMapper.selectById(id);
        if (t == null) {
            throw new BusinessException(ResultCode.FREIGHT_TEMPLATE_NOT_FOUND);
        }
        return t;
    }

    private void apply(FreightTemplate t, FreightTemplateDTO dto) {
        FreightType type = FreightType.of(dto.getType());
        if (type == null) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "类型只能是 FIXED 或 FREE_OVER");
        }
        // 满额包邮却没填门槛 = 永远不包邮，配置错很难被发现，直接在保存时拦住
        if (type == FreightType.FREE_OVER && dto.getFreeThreshold() == null) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "满额包邮必须填写包邮门槛");
        }
        t.setName(dto.getName().trim());
        t.setType(type.name());
        t.setBaseFee(dto.getBaseFee() == null ? BigDecimal.ZERO : dto.getBaseFee());
        t.setFreeThreshold(type == FreightType.FREE_OVER ? dto.getFreeThreshold() : null);
        t.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
    }
}
