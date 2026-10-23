-- ============================================================================
-- 评价晒图：先把图片存本机磁盘，后期换对象存储
--
-- 只存 URL（逗号分隔，最多 3 张），不存二进制：
--   图片本身放在 mall.upload.dir（默认 ./uploads），由 /uploads/** 静态映射对外提供。
--   换成 OSS/MinIO 时只要换 FileStorage 的实现，这一列和接口都不用动。
-- ============================================================================
USE mall;
SET NAMES utf8mb4;

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE t_review ADD COLUMN images VARCHAR(1024) DEFAULT NULL COMMENT ''图片 URL，逗号分隔，最多 3 张''',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 't_review' AND column_name = 'images'
);
PREPARE s1 FROM @ddl;
EXECUTE s1;
DEALLOCATE PREPARE s1;
