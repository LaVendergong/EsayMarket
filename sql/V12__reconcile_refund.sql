-- ============================================================================
-- 退款对账
--
-- V11 只对了「支付」：渠道账单里的退款行被跳过。但退款同样会出错，而且错得更贵：
--   - 我们标了"已退款"，渠道其实没退（用户在等钱）
--   - 渠道退了，我们没记账（对不上账）
--   - 退款金额不一致（部分退款、手续费口径）
--
-- 设计：对账批次增加一组退款计数（和支付计数并列，不混在一起），
--       差异表增加 biz_type 区分是支付差异还是退款差异。
-- ============================================================================
USE mall;
SET NAMES utf8mb4;

SET @ddl = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE t_reconcile_batch
            ADD COLUMN refund_total_lines INT NOT NULL DEFAULT 0 COMMENT ''退款账单条数'',
            ADD COLUMN refund_matched INT NOT NULL DEFAULT 0 COMMENT ''退款一致'',
            ADD COLUMN refund_missing_local INT NOT NULL DEFAULT 0 COMMENT ''渠道有退款、本地无'',
            ADD COLUMN refund_missing_channel INT NOT NULL DEFAULT 0 COMMENT ''本地有退款、渠道无'',
            ADD COLUMN refund_amount_mismatch INT NOT NULL DEFAULT 0 COMMENT ''退款金额不一致''',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 't_reconcile_batch'
      AND column_name = 'refund_total_lines'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl2 = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE t_reconcile_diff
            ADD COLUMN biz_type VARCHAR(10) NOT NULL DEFAULT ''PAY'' COMMENT ''PAY / REFUND'',
            ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT ''商户退款单号（退款差异时使用）''',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 't_reconcile_diff'
      AND column_name = 'biz_type'
);
PREPARE stmt2 FROM @ddl2;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;
