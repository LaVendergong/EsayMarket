-- ============================================================================
-- 刷新令牌表：把「登录态能活多久」从 7 天收短成「access 15 分钟 + refresh 可吊销」
--
-- 为什么需要：原来 access token 有效期 7 天且**无法吊销** —— 登出只是清了浏览器 Cookie，
-- 已经泄露出去的 token 在到期前一直能用。加了这张表之后：
--   - access token 只活 15 分钟（泄露窗口从 7 天缩到 15 分钟）
--   - refresh token 存**哈希**（库泄露也拿不到可用令牌），用一次即作废（轮换）
--   - 登出 = 把这行标记 revoked，之后再也换不出新的 access token
--
-- 存哈希而不是明文：refresh 是"能换出 access 的凭证"，等价于长期口令，
-- 明文入库等于把用户会话交出去。
-- ============================================================================
-- 显式声明本文件是 UTF-8：initdb 用的 mysql 客户端可能默认 latin1，
-- 那样中文会被二次编码存进库（表面看是乱码，实际是数据写坏了）。见 docs §8 第 68 条。
SET NAMES utf8mb4;

USE mall;

CREATE TABLE IF NOT EXISTS t_refresh_token (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL COMMENT '所属用户',
    token_hash  CHAR(64)     NOT NULL COMMENT 'SHA-256(令牌) 的十六进制，明文不入库',
    expires_at  DATETIME     NOT NULL COMMENT '过期时间（默认 7 天）',
    revoked     TINYINT      NOT NULL DEFAULT 0 COMMENT '0-有效 1-已作废（登出或已被轮换）',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_token_hash (token_hash),
    KEY idx_refresh_user (user_id),
    KEY idx_refresh_expires (expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='刷新令牌（可吊销）';
