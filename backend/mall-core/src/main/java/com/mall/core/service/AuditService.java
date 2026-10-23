package com.mall.core.service;

import com.mall.common.result.PageResult;
import com.mall.core.model.vo.AdminAuditLogVO;

/** 后台操作审计 */
public interface AuditService {

    /**
     * 记录一次后台操作。
     *
     * <p>调用方在业务**成功之后**调用；没有登录用户（定时任务）时静默跳过，
     * 写入失败也只打日志 —— 审计不该让业务回滚。
     */
    void record(String action, String targetType, Long targetId, String detail);

    PageResult<AdminAuditLogVO> page(long pageNum, long pageSize, String action);
}
