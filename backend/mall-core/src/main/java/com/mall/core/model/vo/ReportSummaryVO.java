package com.mall.core.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "销售概览")
public class ReportSummaryVO {

    /** 区间内已支付订单的实付金额合计（不含已退款订单） */
    private BigDecimal gmv;

    /** 区间内的退款金额（售后已同意、退款完成在区间内） */
    private BigDecimal refundAmount;

    /** 净收入 = GMV - 退款 */
    private BigDecimal netGmv;

    private long orderCount;

    /** 下单人数（去重） */
    private long buyerCount;

    /** 客单价 = GMV / 订单数 */
    private BigDecimal avgOrderAmount;

    private long newUsers;

    /** 区间内发出的券 */
    private long couponReceived;

    /** 区间内被用掉的券 */
    private long couponUsed;

    /** 核销率（0-100，保留 1 位） */
    private BigDecimal couponUseRate;
}
