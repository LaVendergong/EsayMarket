package com.mall.core.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "后台操作审计记录")
public class AdminAuditLogVO {

    private Long id;

    private Long adminId;

    private String adminName;

    /** 动作 code */
    private String action;

    /** 动作中文名 */
    private String actionText;

    private String targetType;

    private Long targetId;

    private String detail;

    private String ip;

    private LocalDateTime createTime;
}
