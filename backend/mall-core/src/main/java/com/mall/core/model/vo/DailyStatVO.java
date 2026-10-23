package com.mall.core.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Schema(description = "按天的销售统计")
public class DailyStatVO {

    private LocalDate statDate;

    private long orderCount;

    private BigDecimal gmv;
}
