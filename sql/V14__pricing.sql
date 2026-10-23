-- ============================================================================
-- 结算金额模型：运费 + 优惠券 + 金额分摊
--
-- 改之前：`pay_amount = 商品金额之和`。这意味着一分钱的运费都收不了、
-- 一张券都发不出去——运营侧的活动全部无法落地。
--
-- 这次补三件事：
--   1) 运费模板（固定运费 / 满额包邮），挂在商品上；按模板分组计费再求和
--   2) 满减券（模板 + 用户券两步），下单锁定、取消/超时退回、支付核销
--   3) **金额分摊**：订单级优惠要按商品金额比例分摊到每个 order_item，
--      否则"退其中一件该退多少钱"永远算不清（这是本次最重要的结构改动）
--
-- 字段语义（别搞混）：
--   t_order.total_amount     商品金额（历史字段名，含义就是商品总额）
--   t_order.freight_amount   运费
--   t_order.discount_amount  优惠金额（券）
--   t_order.pay_amount       应付 = 商品 + 运费 - 优惠
--   t_order_item.total_amount   该行商品金额（单价 × 数量）
--   t_order_item.discount_amount 该行分摊到的优惠
--   t_order_item.pay_amount     该行实付 = 商品 - 分摊优惠
-- ============================================================================
USE mall;
SET NAMES utf8mb4;

-- ---------------------------------------------------------------- 运费模板
CREATE TABLE IF NOT EXISTS t_freight_template (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    name           VARCHAR(64)  NOT NULL COMMENT '模板名（后台识别用）',
    type           VARCHAR(16)  NOT NULL COMMENT 'FIXED 固定运费 / FREE_OVER 满额包邮',
    base_fee       DECIMAL(10, 2) NOT NULL DEFAULT 0 COMMENT '基础运费',
    free_threshold DECIMAL(10, 2) DEFAULT NULL COMMENT '满多少包邮（FREE_OVER 时有效）',
    status         TINYINT      NOT NULL DEFAULT 1 COMMENT '1 启用 0 停用',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted        TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='运费模板';

-- 商品可绑定运费模板；不绑 = 包邮（0 运费），避免历史商品突然开始收运费
SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE t_product ADD COLUMN freight_template_id BIGINT DEFAULT NULL COMMENT ''运费模板，NULL=包邮''',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 't_product' AND column_name = 'freight_template_id'
);
PREPARE s1 FROM @ddl;
EXECUTE s1;
DEALLOCATE PREPARE s1;

-- ---------------------------------------------------------------- 优惠券模板
CREATE TABLE IF NOT EXISTS t_coupon (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(64)  NOT NULL COMMENT '券名，如「满100减20」',
    threshold   DECIMAL(10, 2) NOT NULL DEFAULT 0 COMMENT '使用门槛（商品金额）',
    amount      DECIMAL(10, 2) NOT NULL COMMENT '优惠金额',
    total       INT          NOT NULL DEFAULT 0 COMMENT '发放总量',
    received    INT          NOT NULL DEFAULT 0 COMMENT '已领取数量',
    valid_from  DATETIME     DEFAULT NULL COMMENT '生效时间，NULL=立即',
    valid_to    DATETIME     DEFAULT NULL COMMENT '失效时间，NULL=长期',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1 可领 0 停发',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='优惠券模板';

-- ---------------------------------------------------------------- 用户券
CREATE TABLE IF NOT EXISTS t_user_coupon (
    id           BIGINT   NOT NULL AUTO_INCREMENT,
    user_id      BIGINT   NOT NULL,
    coupon_id    BIGINT   NOT NULL,
    status       TINYINT  NOT NULL DEFAULT 0 COMMENT '0 未使用 1 已使用（下单锁定/支付核销） 2 已过期',
    order_id     BIGINT   DEFAULT NULL COMMENT '锁定它的订单',
    receive_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    use_time     DATETIME DEFAULT NULL,
    create_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted      TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- 每人每券只能领一张：并发领取时靠唯一键兜底，而不是先查再插
    UNIQUE KEY uk_user_coupon (user_id, coupon_id),
    KEY idx_user_coupon_status (user_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='用户优惠券';

-- ---------------------------------------------------------------- 订单金额
SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE t_order
            ADD COLUMN freight_amount DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT ''运费'',
            ADD COLUMN discount_amount DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT ''优惠金额'',
            ADD COLUMN user_coupon_id BIGINT DEFAULT NULL COMMENT ''使用的用户券''',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 't_order' AND column_name = 'freight_amount'
);
PREPARE s2 FROM @ddl;
EXECUTE s2;
DEALLOCATE PREPARE s2;

-- ---------------------------------------------------------------- 订单项金额
SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE t_order_item
            ADD COLUMN discount_amount DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT ''本行分摊到的优惠'',
            ADD COLUMN pay_amount DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT ''本行实付 = 商品金额 - 分摊优惠''',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 't_order_item' AND column_name = 'discount_amount'
);
PREPARE s3 FROM @ddl;
EXECUTE s3;
DEALLOCATE PREPARE s3;

-- 历史订单：把老数据的 item.pay_amount 补成商品金额（没有优惠/运费的年代）
UPDATE t_order_item SET pay_amount = total_amount WHERE pay_amount = 0 AND total_amount > 0;
