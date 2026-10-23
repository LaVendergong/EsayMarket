package com.mall.core.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "数据维护概览")
public class MaintenanceVO {

    private List<TableStatVO> tables;

    /** 搜索日志保留天数 */
    private int searchLogRetainDays;

    /** 最近一次清理时间（进程内记录，重启后为空） */
    private LocalDateTime searchLogLastCleanAt;

    /** 最近一次清理删除的行数 */
    private long searchLogLastDeleted;
}
