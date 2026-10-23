package com.mall.core.service.impl;

import com.mall.common.exception.BusinessException;
import com.mall.common.result.ResultCode;
import com.mall.core.entity.FreightTemplate;
import com.mall.core.mapper.FreightTemplateMapper;
import com.mall.core.mapper.ProductMapper;
import com.mall.core.model.dto.FreightTemplateDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 运费模板两类配置错误：满额包邮没填门槛、删掉还被商品引用的模板 */
@ExtendWith(MockitoExtension.class)
class FreightServiceImplTest {

    @Mock private FreightTemplateMapper freightTemplateMapper;
    @Mock private ProductMapper productMapper;

    private FreightServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FreightServiceImpl(freightTemplateMapper, productMapper);
    }

    private FreightTemplateDTO dto(String type) {
        FreightTemplateDTO d = new FreightTemplateDTO();
        d.setName("标准快递");
        d.setType(type);
        d.setBaseFee(new BigDecimal("8.00"));
        return d;
    }

    private FreightTemplate template(long id) {
        FreightTemplate t = new FreightTemplate();
        t.setId(id);
        t.setName("标准快递");
        t.setType("FIXED");
        t.setBaseFee(new BigDecimal("8.00"));
        t.setStatus(1);
        return t;
    }

    @Test
    void create_freeOverWithoutThreshold_isRejected() {
        BusinessException e = catchThrowableOfType(() -> service.create(dto("FREE_OVER")), BusinessException.class);

        assertThat(e.getCode()).isEqualTo(ResultCode.VALIDATE_FAILED.getCode());
        verify(freightTemplateMapper, never()).insert(any(FreightTemplate.class));
    }

    @Test
    void create_withUnknownType_isRejected() {
        BusinessException e = catchThrowableOfType(() -> service.create(dto("BY_WEIGHT")), BusinessException.class);

        assertThat(e.getCode()).isEqualTo(ResultCode.VALIDATE_FAILED.getCode());
    }

    @Test
    void create_fixedTemplate_dropsFreeThreshold() {
        FreightTemplateDTO d = dto("FIXED");
        d.setFreeThreshold(new BigDecimal("99.00"));

        service.create(d);

        ArgumentCaptor<FreightTemplate> captor = ArgumentCaptor.forClass(FreightTemplate.class);
        verify(freightTemplateMapper).insert(captor.capture());
        assertThat(captor.getValue().getFreeThreshold()).isNull();
        assertThat(captor.getValue().getStatus()).isEqualTo(1);
    }

    @Test
    void delete_whenProductsStillBound_isRejected() {
        when(freightTemplateMapper.selectById(1L)).thenReturn(template(1L));
        when(productMapper.selectCount(any())).thenReturn(2L);

        BusinessException e = catchThrowableOfType(() -> service.delete(1L), BusinessException.class);

        assertThat(e.getCode()).isEqualTo(ResultCode.VALIDATE_FAILED.getCode());
        verify(freightTemplateMapper, never()).deleteById(anyLong());
    }
}
