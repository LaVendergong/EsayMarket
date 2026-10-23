-- ============================================================================
-- 后台操作审计
--
-- 记录「谁在什么时候对什么做了什么」：改商品、发货、退款、隐藏评价、导卡密。
-- 埋点放在 admin 的 Controller 层（业务成功后记一条），不动 Service 的构造依赖，
-- 也不会因为审计失败影响业务（AuditServiceImpl 里吞异常只打 warn）。
-- ============================================================================
USE mall;
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS t_admin_audit_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    admin_id    BIGINT       NOT NULL,
    /** 冗余一份用户名：账号改名后历史记录仍然读得懂 */
    admin_name  VARCHAR(64)  DEFAULT NULL,
    /** 动作 code，见 AuditAction */
    action      VARCHAR(48)  NOT NULL,
    target_type VARCHAR(32)  DEFAULT NULL,
    target_id   BIGINT       DEFAULT NULL,
    detail      VARCHAR(500) DEFAULT NULL,
    ip          VARCHAR(64)  DEFAULT NULL,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_action (action, id),
    KEY idx_admin (admin_id, id),
    KEY idx_time (create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='后台操作审计日志';
