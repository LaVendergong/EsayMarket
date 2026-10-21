-- ============================================================
-- V4：超时关单扫描用的联合索引（幂等，可重复执行）
-- 扫描条件：WHERE status = 0 AND expire_time <= NOW()
-- 用法：mysql -uroot -p*** --default-character-set=utf8mb4 -e "source D:/projects/mall/sql/V4__order_index.sql"
-- ============================================================
-- 显式声明本文件是 UTF-8：initdb 用的 mysql 客户端可能默认 latin1，
-- 那样中文会被二次编码存进库（表面看是乱码，实际是数据写坏了）。见 docs §8 第 68 条。
SET NAMES utf8mb4;

USE mall;

SET @exists := (SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 't_order'
                  AND index_name = 'idx_status_expire');

SET @ddl := IF(@exists = 0,
               'ALTER TABLE t_order ADD KEY idx_status_expire (status, expire_time)',
               'DO 0');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
