package com.mall.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mall.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 后台操作审计 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_admin_audit_log")
public class AdminAuditLog extends BaseEntity {

    private Long adminId;

    /** 冗余用户名：账号改名后历史记录仍可读 */
    private String adminName;

    /** 动作 code，见 AuditAction */
    private String action;

    private String targetType;

    private Long targetId;

    private String detail;

    private String ip;
}
