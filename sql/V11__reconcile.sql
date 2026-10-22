-- ============================================================================
-- 对账（渠道账单 vs 本地支付记录）
--
-- 前面做了「回调 + 主动查单」两道兜底，但那都是**单笔**的、且都依赖"我们知道要查哪一笔"。
-- 真正的兜底是**逐笔对账**：拿渠道给的账单，和本地记录逐一比对。
-- 它回答的是另一个问题：有没有哪一笔钱，渠道收了、我们不知道（或反之）。
--
-- 设计：
--   t_reconcile_batch  一次对账的汇总（渠道 / 账单日 / 各类差异条数）
--   t_reconcile_diff   逐笔差异（这才是要人工处理的清单）
--
-- 差异类型：
--   MISSING_LOCAL    渠道账单里有、本地没有（**最危险**：钱收了但没记账）
--   MISSING_CHANNEL  本地有、渠道账单里没有（本地以为收钱了，其实没有）
--   AMOUNT_MISMATCH  两边都有但金额不一致
--
-- v1 只对「支付」，退款对账留到下一版（见 docs 的已知缺口）。
-- ============================================================================
USE mall;
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS t_reconcile_batch (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    channel           VARCHAR(20) NOT NULL COMMENT 'MOCK / ALIPAY_WAP / WECHAT_NATIVE',
    bill_date         DATE        NOT NULL COMMENT '账单日期',
    total_lines       INT         NOT NULL DEFAULT 0 COMMENT '账单条数',
    matched           INT         NOT NULL DEFAULT 0 COMMENT '完全一致',
    missing_local     INT         NOT NULL DEFAULT 0 COMMENT '渠道有、本地无',
    missing_channel   INT         NOT NULL DEFAULT 0 COMMENT '本地有、渠道无',
    amount_mismatch   INT         NOT NULL DEFAULT 0 COMMENT '金额不一致',
    status            TINYINT     NOT NULL DEFAULT 0 COMMENT '0 有差异 1 完全一致',
    create_time       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted           TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_reconcile_channel_date (channel, bill_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='对账批次';

CREATE TABLE IF NOT EXISTS t_reconcile_diff (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    batch_id          BIGINT       NOT NULL,
    diff_type         VARCHAR(20)  NOT NULL COMMENT 'MISSING_LOCAL / MISSING_CHANNEL / AMOUNT_MISMATCH',
    payment_no        VARCHAR(40)  DEFAULT NULL COMMENT '商户支付单号',
    channel_trade_no  VARCHAR(64)  DEFAULT NULL,
    local_amount      DECIMAL(10, 2) DEFAULT NULL,
    channel_amount    DECIMAL(10, 2) DEFAULT NULL,
    detail            VARCHAR(200) DEFAULT NULL,
    create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted           TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_reconcile_diff_batch (batch_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='对账差异';
