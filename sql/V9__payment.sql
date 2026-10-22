-- ============================================================================
-- 支付单
--
-- 改之前：「支付」就是 `POST /order/{id}/pay` 直接把订单状态改成已支付 ——
-- 没有支付单、没有渠道、没有回调、没有对账依据。用户看到的"去支付"其实什么也没发生。
--
-- 这笔表解决四件事：
--   1. 每一次支付尝试都有独立记录（payment_no），订单可以有多条失败/关闭的支付单
--   2. 记下渠道（MOCK / ALIPAY_WAP / WECHAT_NATIVE）和渠道交易号，方便对账与排查
--   3. 回调幂等：同一笔支付即使收到多次通知，只有第一次能把订单推进到已支付
--   4. 金额快照：回调里的金额必须与支付单一致，否则拒绝（防篡改）
-- ============================================================================
USE mall;
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS t_payment (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    payment_no       VARCHAR(40)   NOT NULL COMMENT '商户支付单号（P + 时间 + 随机）',
    order_id         BIGINT        NOT NULL,
    order_no         VARCHAR(32)   NOT NULL COMMENT '冗余订单号，回调/对账直接看',
    user_id          BIGINT        NOT NULL,
    channel          VARCHAR(20)   NOT NULL COMMENT 'MOCK / ALIPAY_WAP / WECHAT_NATIVE',
    amount           DECIMAL(10, 2) NOT NULL COMMENT '应付金额（快照）',
    status           TINYINT       NOT NULL DEFAULT 0 COMMENT '0-待支付 1-已支付 2-已关闭 3-支付失败',
    pay_params       TEXT          DEFAULT NULL COMMENT '调起参数（支付宝跳转 URL / 微信 code_url 的 JSON）',
    channel_trade_no VARCHAR(64)   DEFAULT NULL COMMENT '渠道交易号（支付宝 trade_no / 微信 transaction_id）',
    paid_time        DATETIME      DEFAULT NULL,
    expire_time      DATETIME      DEFAULT NULL COMMENT '支付单有效期，超过后不允许再支付',
    notify_time      DATETIME      DEFAULT NULL COMMENT '最后一次回调时间',
    notify_raw       TEXT          DEFAULT NULL COMMENT '回调原文（排查用，不含敏感信息）',
    create_time      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted          TINYINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_no (payment_no),
    KEY idx_payment_order (order_id),
    KEY idx_payment_status (status, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='支付单';
