-- ============================================================================
-- 退款真正打到渠道
--
-- 改之前：售后「同意退款」只是把订单标成已退款 + 恢复库存 —— **钱一分没退**。
-- 真实业务里这是硬伤：用户看到"已退款"，账户里却没有钱。
--
-- 这里给售后单补上退款执行结果：
--   refund_no      商户退款单号（由售后单号派生，**必须可重复**，渠道按它做幂等）
--   refund_channel 原支付渠道（MOCK / ALIPAY_WAP / WECHAT_NATIVE）
--   refund_status  0 未退款 1 退款中 2 退款成功 3 退款失败
--   refund_error   失败原因（给客服看的，不是给用户看的）
--
-- 为什么单独记 refund_status 而不是复用 status：
--   退款是**外部动作**，可能"已同意但渠道失败"。状态分开才能既不骗用户、
--   又让客服能重试（重试会用同一个 refund_no，不会重复退钱）。
-- ============================================================================
USE mall;
SET NAMES utf8mb4;

-- MySQL 8 没有 ADD COLUMN IF NOT EXISTS，用 information_schema 做幂等
SET @ddl = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE t_after_sale
            ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT ''商户退款单号（渠道按它幂等）'',
            ADD COLUMN refund_channel VARCHAR(20) DEFAULT NULL COMMENT ''原支付渠道'',
            ADD COLUMN refund_status TINYINT NOT NULL DEFAULT 0 COMMENT ''0 未退款 1 退款中 2 退款成功 3 退款失败'',
            ADD COLUMN refund_time DATETIME DEFAULT NULL COMMENT ''退款成功/受理时间'',
            ADD COLUMN refund_error VARCHAR(200) DEFAULT NULL COMMENT ''退款失败原因''',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 't_after_sale'
      AND column_name = 'refund_no'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
