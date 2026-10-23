-- ============================================================================
-- 优惠券 / 运费模板演示数据（幂等，可重复执行）
--
-- 为什么需要这个文件：V14 只建了表，没有任何券和运费模板，
-- 于是「领券中心」永远是空的、结算页运费永远是 0，功能等于没上线。
--
-- 两个演示场景（方便肉眼验证计价）：
--   - 手机/笔记本（单价都 > 99）走「满 99 包邮」 → 运费 0
--   - 男装 399 元走「标准快递 8 元」           → 运费 8，便于看分摊后的应付
--
-- 注意：ON DUPLICATE KEY UPDATE 里**故意不写 received**。
--       重复执行种子脚本时若把已领数量清零，会和 t_user_coupon 的实际条数脱钩，
--       条件 UPDATE「received < total」就会放行超发。
-- ============================================================================
USE mall;
SET NAMES utf8mb4;

INSERT INTO t_freight_template (id, name, type, base_fee, free_threshold, status, deleted) VALUES
  (1, '标准快递 8 元', 'FIXED',     8.00, NULL,  1, 0),
  (2, '满 99 包邮',    'FREE_OVER', 8.00, 99.00, 1, 0)
ON DUPLICATE KEY UPDATE name = VALUES(name), type = VALUES(type), base_fee = VALUES(base_fee),
                        free_threshold = VALUES(free_threshold), status = VALUES(status), deleted = 0;

UPDATE t_product SET freight_template_id = 2 WHERE id IN (1, 2, 3);
UPDATE t_product SET freight_template_id = 1 WHERE id IN (4, 5);

INSERT INTO t_coupon (id, name, threshold, amount, total, received, status, deleted) VALUES
  (1, '满 100 减 20',   100.00,  20.00,  1000, 0, 1, 0),
  (2, '满 5000 减 500', 5000.00, 500.00, 100,  0, 1, 0)
ON DUPLICATE KEY UPDATE name = VALUES(name), threshold = VALUES(threshold), amount = VALUES(amount),
                        total = VALUES(total), status = VALUES(status), deleted = 0;
