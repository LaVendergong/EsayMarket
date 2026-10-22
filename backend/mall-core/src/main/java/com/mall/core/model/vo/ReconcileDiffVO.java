package com.mall.core.model.vo;

import com.mall.core.entity.ReconcileDiff;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "对账差异")
public class ReconcileDiffVO {

    private Long id;

    private String diffType;

    private String diffTypeText;

    private String paymentNo;

    private String channelTradeNo;

    private BigDecimal localAmount;

    private BigDecimal channelAmount;

    private String detail;

    public static ReconcileDiffVO from(ReconcileDiff d) {
        ReconcileDiffVO vo = new ReconcileDiffVO();
        vo.setId(d.getId());
        vo.setDiffType(d.getDiffType());
        vo.setDiffTypeText(textOf(d.getDiffType()));
        vo.setPaymentNo(d.getPaymentNo());
        vo.setChannelTradeNo(d.getChannelTradeNo());
        vo.setLocalAmount(d.getLocalAmount());
        vo.setChannelAmount(d.getChannelAmount());
        vo.setDetail(d.getDetail());
        return vo;
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
