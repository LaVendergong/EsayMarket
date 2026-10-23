package com.mall.core.model.vo;

import com.mall.core.entity.FreightTemplate;
import com.mall.core.enums.FreightType;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class FreightTemplateVO {

    private Long id;
    private String name;
    private String type;
    private String typeText;
    private BigDecimal baseFee;
    private BigDecimal freeThreshold;
    private Integer status;

    public static FreightTemplateVO from(FreightTemplate t) {
        FreightTemplateVO vo = new FreightTemplateVO();
        vo.setId(t.getId());
        vo.setName(t.getName());
        vo.setType(t.getType());
        FreightType type = FreightType.of(t.getType());
        vo.setTypeText(type == null ? t.getType() : type.getText());
        vo.setBaseFee(t.getBaseFee());
        vo.setFreeThreshold(t.getFreeThreshold());
        vo.setStatus(t.getStatus());
        return vo;
    }
}
