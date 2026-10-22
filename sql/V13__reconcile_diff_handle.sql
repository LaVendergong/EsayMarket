-- ============================================================================
-- 对账差异的处理
--
-- 之前差异只能看、不能处理：这张表只会越积越多，运营最后就不看了。
-- 这一步给它加上处理状态与审计字段（谁、什么时候、按什么理由处理的）。
--
-- 刻意**只做审计，不做资金操作**：
--   「补记一笔钱」和「重新退款」都是不可逆的资金动作，必须走已有的
--   「同步支付」「查询退款」接口并人工确认，不能在一个"标记已处理"的按钮里顺手做掉。
--   这一版负责把差异**归档清楚**，资金动作仍走原链路。
--
-- handle_status: 0 待处理 / 1 已处理 / 2 已忽略
-- ============================================================================
USE mall;
SET NAMES utf8mb4;

SET @ddl = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE t_reconcile_diff
            ADD COLUMN handle_status TINYINT NOT NULL DEFAULT 0 COMMENT ''0 待处理 1 已处理 2 已忽略'',
            ADD COLUMN handle_action VARCHAR(20) DEFAULT NULL COMMENT ''CONFIRM / IGNORE'',
            ADD COLUMN handle_remark VARCHAR(200) DEFAULT NULL COMMENT ''处理说明'',
            ADD COLUMN handled_by VARCHAR(64) DEFAULT NULL COMMENT ''处理人'',
            ADD COLUMN handle_time DATETIME DEFAULT NULL',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 't_reconcile_diff'
      AND column_name = 'handle_status'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 按批次查"还有几条没处理"会用到
SET @idx = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE t_reconcile_diff ADD KEY idx_reconcile_diff_handle (batch_id, handle_status)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 't_reconcile_diff'
      AND index_name = 'idx_reconcile_diff_handle'
);
PREPARE stmt2 FROM @idx;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;
