-- ============================================================================
-- 商品评价
--
-- 规则（产品已定）：
--   1) 只有「已完成」的订单能评价（虚拟商品支付即完成，所以也能评）
--   2) 一个订单项只能评一次 —— 用唯一键兜底，不靠"先查再插"
--   3) 评价默认直接展示，后台只能事后隐藏（不做先审后发）
--
-- 为什么把评分冗余到 t_product：
--   商品列表/详情要显示"4.8 分 128 条"，每次实时 GROUP BY 聚合不划算，
--   评价新增/隐藏时重算一次写回商品即可（数据量小，一条 UPDATE 搞定）。
-- ============================================================================
USE mall;
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS t_review (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    order_id      BIGINT      NOT NULL,
    order_item_id BIGINT      NOT NULL,
    user_id       BIGINT      NOT NULL,
    product_id    BIGINT      NOT NULL,
    sku_id        BIGINT      NOT NULL,
    /** 1-5 星 */
    rating        TINYINT     NOT NULL,
    content       VARCHAR(500) DEFAULT NULL,
    /** 1 匿名（前台只显示"匿名用户"） */
    anonymous     TINYINT     NOT NULL DEFAULT 0,
    /** 1 展示 0 已被后台隐藏 */
    status        TINYINT     NOT NULL DEFAULT 1,
    create_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted       TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- 一个订单项只能评一次；并发重复提交会撞这个键，捕获后提示"已评价过"
    UNIQUE KEY uk_order_item (order_item_id),
    KEY idx_product (product_id, status, id),
    KEY idx_user (user_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='商品评价';

-- ---------------------------------------------------------------- 商品上的评分冗余
SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE t_product
            ADD COLUMN rating_avg DECIMAL(2,1) NOT NULL DEFAULT 0.0 COMMENT ''平均分（仅统计展示中的评价）'',
            ADD COLUMN review_count INT NOT NULL DEFAULT 0 COMMENT ''评价条数''',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 't_product' AND column_name = 'rating_avg'
);
PREPARE s1 FROM @ddl;
EXECUTE s1;
DEALLOCATE PREPARE s1;
