-- ============================================================
-- V5：下单幂等键（幂等，可重复执行）
--
-- 背景：create() 的流程是「读购物车选中项 → 锁库存 → 建单 → 清选中项」。
--       双击提交 / 网络重试 / 多标签页并发时，两个请求会读到同一批选中项，
--       各自建一单并各锁一次库存 —— 库存不会超卖，但会凭空多占库存并产生重复订单。
--
-- 解决：order 表上放一个客户端生成的 request_id，用 (user_id, request_id) 唯一键兜底；
--       调用方在【一次结算】里固定不变地传同一个值，重试就会命中同一单。
--
-- 为什么键是 (user_id, request_id) 而不是只有 request_id：
--       服务端查幂等单时会带上 user_id（防止串号），如果唯一键只有 request_id，
--       两个用户传了同一个 request_id 时会出现「查不到、插入又撞键」的 500。
--
-- 允许 NULL：老客户端不传时退化为「靠购物车行锁串行化」的普通下单；
--       MySQL 唯一索引允许多个 NULL，所以历史数据不受影响。
--
-- 用法：mysql -uroot -p*** --default-character-set=utf8mb4 -e "source D:/projects/mall/sql/V5__order_request_id.sql"
-- ============================================================
USE mall;

SET @exists := (SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 't_order'
                  AND column_name = 'request_id');

SET @ddl := IF(@exists = 0,
               'ALTER TABLE t_order ADD COLUMN request_id VARCHAR(64) DEFAULT NULL COMMENT ''下单幂等键（客户端生成）'' AFTER order_no',
               'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exists := (SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 't_order'
                  AND index_name = 'uk_user_request');

SET @ddl := IF(@exists = 0,
               'ALTER TABLE t_order ADD UNIQUE KEY uk_user_request (user_id, request_id)',
               'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
