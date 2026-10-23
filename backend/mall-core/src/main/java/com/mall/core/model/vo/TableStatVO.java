package com.mall.core.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "表行数统计")
public class TableStatVO {

    private String name;

    /** 行数（别叫 rows：MySQL 8 里 ROWS 是保留字，反引号又容易在别的方言里炸） */
    private Long rowCount;
}
