-- 显式选库：手工 mysql < file 导入时也能用（docker initdb 本来就会选库）
USE mall;
-- 显式声明本文件是 UTF-8：initdb 用的 mysql 客户端可能默认 latin1，
-- 那样中文会被二次编码存进库（表面看是乱码，实际是数据写坏了）。见 docs §8 第 68 条。
SET NAMES utf8mb4;

ALTER TABLE t_user ADD COLUMN role TINYINT NOT NULL DEFAULT 0 COMMENT '0-普通用户 1-管理员' AFTER avatar;