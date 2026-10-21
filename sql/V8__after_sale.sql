-- ============================================================================
-- 售后单（退款 / 退货退款）
--
-- 之前系统里**完全没有售后**：订单只有 待支付/已支付/已发货/已完成/已关闭，
-- 用户付了钱发现不想要，唯一的出路是「不确认收货」，没有任何可走的流程。
--
-- 设计要点：
--   1. 售后单与订单是 1:N（一笔订单可以先被拒绝、再重新申请），
--      但**同一时刻只允许一个进行中**的单 —— 靠订单状态机保证（见 AfterSaleServiceImpl）
--   2. order_status_before 记录申请前的订单状态，商家拒绝 / 用户撤销时按它恢复
--   3. refund_amount 记下当时实付金额，避免之后订单金额变动导致退款金额漂移
--   4. 状态：0 待商家处理 → 1 已同意(已退款) / 2 已拒绝 / 3 用户已撤销
-- ============================================================================
USE mall;
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS t_after_sale (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    after_sale_no       VARCHAR(32)  NOT NULL COMMENT '售后单号（AS + 时间 + 随机）',
    order_id            BIGINT       NOT NULL COMMENT '订单 id',
    order_no            VARCHAR(32)  NOT NULL COMMENT '冗余订单号，列表直接展示不用再 join',
    user_id             BIGINT       NOT NULL COMMENT '申请人',
    type                TINYINT      NOT NULL COMMENT '1-仅退款 2-退货退款',
    status              TINYINT      NOT NULL DEFAULT 0 COMMENT '0-待商家处理 1-已同意(已退款) 2-已拒绝 3-用户已撤销',
    reason              VARCHAR(64)  NOT NULL COMMENT '申请原因（固定选项，便于统计）',
    description         VARCHAR(500) DEFAULT NULL COMMENT '补充说明',
    refund_amount       DECIMAL(10, 2) NOT NULL COMMENT '退款金额（快照）',
    order_status_before TINYINT      NOT NULL COMMENT '申请前的订单状态，拒绝/撤销时恢复',
    apply_time          DATETIME     NOT NULL,
    handle_time         DATETIME     DEFAULT NULL COMMENT '商家处理时间',
    handle_remark       VARCHAR(200) DEFAULT NULL COMMENT '商家处理备注',
    create_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted             TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_after_sale_no (after_sale_no),
    KEY idx_after_sale_user (user_id),
    KEY idx_after_sale_order (order_id),
    KEY idx_after_sale_status (status, apply_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='售后单';
