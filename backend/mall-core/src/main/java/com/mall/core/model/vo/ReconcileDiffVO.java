package com.mall.core.model.vo;

import com.mall.core.entity.ReconcileDiff;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "对账差异")
public class ReconcileDiffVO {

    private Long id;

    /** PAY / REFUND */
    private String bizType;

    private String refundNo;

    private String diffType;

    private String diffTypeText;

    private String paymentNo;

    private String channelTradeNo;

    private BigDecimal localAmount;

    private BigDecimal channelAmount;

    private String detail;

    private Integer handleStatus;

    private String handleStatusText;

    private String handleAction;

    private String handleRemark;

    private String handledBy;

    private java.time.LocalDateTime handleTime;

    public static ReconcileDiffVO from(ReconcileDiff d) {
        ReconcileDiffVO vo = new ReconcileDiffVO();
        vo.setId(d.getId());
        vo.setBizType(d.getBizType());
        vo.setRefundNo(d.getRefundNo());
        vo.setDiffType(d.getDiffType());
        vo.setDiffTypeText(textOf(d.getDiffType()));
        vo.setPaymentNo(d.getPaymentNo());
        vo.setChannelTradeNo(d.getChannelTradeNo());
        vo.setLocalAmount(d.getLocalAmount());
        vo.setChannelAmount(d.getChannelAmount());
        vo.setDetail(d.getDetail());
        vo.setHandleStatus(d.getHandleStatus() == null ? 0 : d.getHandleStatus());
        vo.setHandleStatusText(handleText(vo.getHandleStatus()));
        vo.setHandleAction(d.getHandleAction());
        vo.setHandleRemark(d.getHandleRemark());
        vo.setHandledBy(d.getHandledBy());
        vo.setHandleTime(d.getHandleTime());
        return vo;
    }

    public static String handleText(Integer status) {
        if (status == null || status == 0) {
            return "待处理";
        }
        return status == 2 ? "已忽略" : "已处理";
    }

    public static String textOf(String type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case "MISSING_LOCAL" -> "渠道有、本地无";
            case "MISSING_CHANNEL" -> "本地有、渠道无";
            case "AMOUNT_MISMATCH" -> "金额不一致";
            default -> type;
        };
    }
}
