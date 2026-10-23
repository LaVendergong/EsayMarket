-- ============================================================================
-- 商品收藏
--
-- 用唯一键 (user_id, product_id) 保证「一个人对同一商品只有一条」，
-- 取消收藏走逻辑删除，再次收藏时把 deleted 置回 0（否则会撞唯一键）。
-- ============================================================================
USE mall;
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS t_favorite (
    id         BIGINT   NOT NULL AUTO_INCREMENT,
    user_id    BIGINT   NOT NULL,
    product_id BIGINT   NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted    TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_product (user_id, product_id),
    KEY idx_user (user_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='商品收藏';
