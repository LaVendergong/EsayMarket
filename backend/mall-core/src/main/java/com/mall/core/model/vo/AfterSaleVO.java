package com.mall.core.model.vo;

import com.mall.core.entity.AfterSale;
import com.mall.core.enums.AfterSaleStatus;
import com.mall.core.enums.AfterSaleType;
import com.mall.core.enums.RefundStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "售后单")
public class AfterSaleVO {

    private Long id;

    private String afterSaleNo;

    private Long orderId;

    private String orderNo;

    /** 1-仅退款 2-退货退款 */
    private Integer type;

    private String typeText;

    /** 0 待处理 1 已退款 2 已拒绝 3 已撤销 */
    private Integer status;

    private String statusText;

    private String reason;

    private String description;

    private BigDecimal refundAmount;

    private LocalDateTime applyTime;

    private LocalDateTime handleTime;

    private String handleRemark;

    /** 退款执行情况（和售后单状态分开看） */
    private String refundNo;

    private String refundChannel;

    private Integer refundStatus;

    private String refundStatusText;

    private LocalDateTime refundTime;

    private String refundError;

    /** 订单商品快照：只在详情里填，列表不查（避免 N+1） */
    private List<OrderItemVO> items;

    public static AfterSaleVO from(AfterSale a) {
        AfterSaleVO vo = new AfterSaleVO();
        vo.setId(a.getId());
        vo.setAfterSaleNo(a.getAfterSaleNo());
        vo.setOrderId(a.getOrderId());
        vo.setOrderNo(a.getOrderNo());
        vo.setType(a.getType());
        vo.setTypeText(AfterSaleType.textOf(a.getType()));
        vo.setStatus(a.getStatus());
        vo.setStatusText(AfterSaleStatus.textOf(a.getStatus()));
        vo.setReason(a.getReason());
        vo.setDescription(a.getDescription());
        vo.setRefundAmount(a.getRefundAmount());
        vo.setApplyTime(a.getApplyTime());
        vo.setHandleTime(a.getHandleTime());
        vo.setHandleRemark(a.getHandleRemark());
        vo.setRefundNo(a.getRefundNo());
        vo.setRefundChannel(a.getRefundChannel());
        vo.setRefundStatus(a.getRefundStatus());
        vo.setRefundStatusText(RefundStatus.textOf(a.getRefundStatus()));
        vo.setRefundTime(a.getRefundTime());
        vo.setRefundError(a.getRefundError());
        return vo;
    }
}
