-- ============================================================================
-- 敏感字段（PII）字段级加密：先把列加宽
--
-- 加密格式是 `enc:v1:` + base64url(12B IV + 密文 + 16B tag)，比明文长得多：
--   11 位手机号 → 约 59 字符；32 字中文地址 → 约 200 字符。
-- 原来的 VARCHAR(20)/VARCHAR(64) 放不下，必须先加宽再写入密文，否则报 data too long。
--
-- 幂等：MODIFY COLUMN 可以重复执行。新库直接按 V1 的宽度建表，这一条对它们是空操作。
-- 存量明文由应用启动时的 com.mall.core.crypto.PiiEncryptionMigrator 逐行加密（幂等）。
-- 用法：mysql -uroot -p*** --default-character-set=utf8mb4 -e "source D:/projects/mall/sql/V6__pii_encryption.sql"
-- ============================================================================
-- 显式声明本文件是 UTF-8：initdb 用的 mysql 客户端可能默认 latin1，
-- 那样中文会被二次编码存进库（表面看是乱码，实际是数据写坏了）。见 docs §8 第 68 条。
SET NAMES utf8mb4;

USE mall;

ALTER TABLE t_user
    MODIFY COLUMN phone VARCHAR(128) DEFAULT NULL COMMENT '手机号（AES-GCM 密文）';

ALTER TABLE t_address
    MODIFY COLUMN receiver VARCHAR(256) NOT NULL COMMENT '收货人（AES-GCM 密文）',
    MODIFY COLUMN phone VARCHAR(128) NOT NULL COMMENT '联系电话（AES-GCM 密文）',
    MODIFY COLUMN detail VARCHAR(1024) NOT NULL COMMENT '详细门牌（AES-GCM 密文）';

ALTER TABLE t_order
    MODIFY COLUMN receiver VARCHAR(256) DEFAULT NULL COMMENT '收货人快照（AES-GCM 密文）',
    MODIFY COLUMN receiver_phone VARCHAR(128) DEFAULT NULL COMMENT '联系电话快照（AES-GCM 密文）',
    MODIFY COLUMN receiver_addr VARCHAR(2048) DEFAULT NULL COMMENT '地址快照（AES-GCM 密文）';
