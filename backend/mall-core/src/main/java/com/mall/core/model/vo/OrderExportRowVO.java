package com.mall.core.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 订单导出行（只用于拼 CSV，不对外输出 JSON） */
@Data
@Schema(description = "订单导出行")
public class OrderExportRowVO {

    private Long id;

    private String orderNo;

    private LocalDateTime createTime;

    private LocalDateTime payTime;

    private Integer status;

    private Long userId;

    private BigDecimal totalAmount;

    private BigDecimal freightAmount;

    private BigDecimal discountAmount;

    private BigDecimal payAmount;

    private Integer orderType;

    private String shipCompany;

    private String shipNo;

    private String receiver;

    /** 订单内商品名，逗号拼接（GROUP_CONCAT） */
    private String items;
}
