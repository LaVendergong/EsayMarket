package com.mall.core.model.vo;

import com.mall.core.entity.ReconcileBatch;
import com.mall.core.enums.PayChannel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "对账批次")
public class ReconcileBatchVO {

    private Long id;

    private String channel;

    private String channelText;

    private LocalDate billDate;

    private Integer totalLines;

    private Integer matched;

    private Integer missingLocal;

    private Integer missingChannel;

    private Integer amountMismatch;

    // ---- 退款侧 ----
    private Integer refundTotalLines;

    private Integer refundMatched;

    private Integer refundMissingLocal;

    private Integer refundMissingChannel;

    private Integer refundAmountMismatch;

    private Integer status;

    /** 有差异 / 完全一致 */
    private String statusText;

    private LocalDateTime createTime;

    /** 还有几条差异没处理（列表与详情都会填） */
    private Integer unhandledCount;

    /** 只在详情里填（列表不查，避免 N+1） */
    private List<ReconcileDiffVO> diffs;

    public static ReconcileBatchVO from(ReconcileBatch b) {
        ReconcileBatchVO vo = new ReconcileBatchVO();
        vo.setId(b.getId());
        vo.setChannel(b.getChannel());
        vo.setChannelText(PayChannel.textOf(b.getChannel()));
        vo.setBillDate(b.getBillDate());
        vo.setTotalLines(b.getTotalLines());
        vo.setMatched(b.getMatched());
        vo.setMissingLocal(b.getMissingLocal());
        vo.setMissingChannel(b.getMissingChannel());
        vo.setAmountMismatch(b.getAmountMismatch());
        vo.setRefundTotalLines(b.getRefundTotalLines());
        vo.setRefundMatched(b.getRefundMatched());
        vo.setRefundMissingLocal(b.getRefundMissingLocal());
        vo.setRefundMissingChannel(b.getRefundMissingChannel());
        vo.setRefundAmountMismatch(b.getRefundAmountMismatch());
        vo.setStatus(b.getStatus());
        vo.setStatusText(b.getStatus() != null && b.getStatus() == 1 ? "完全一致" : "有差异");
        vo.setCreateTime(b.getCreateTime());
        return vo;
    }
}
