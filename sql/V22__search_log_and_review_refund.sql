-- ============================================================================
-- 搜索日志（热词）+ 评价的退款标记
--
-- 1) t_search_log：只记关键词和时间，不记结果、不记用户身份（热词统计不需要）。
--    不做实时聚合，按天统计 TopN 就够了；量大了按天归档/删旧数据。
-- 2) t_review.refunded：订单退款后，这条评价仍然保留（用户确实写过），
--    但不再计入商品评分 —— 交易已经取消，不该继续影响口碑。
--    同时 refund 成功后要把 product.sales 回退，否则销量只增不减。
-- ============================================================================
USE mall;
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS t_search_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    keyword     VARCHAR(64)  NOT NULL,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_time (create_time),
    KEY idx_keyword (keyword)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='搜索关键词日志（热词统计用）';

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE t_review ADD COLUMN refunded TINYINT NOT NULL DEFAULT 0 COMMENT ''1 该订单已退款（不计入商品评分）''',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 't_review' AND column_name = 'refunded'
);
PREPARE s1 FROM @ddl;
EXECUTE s1;
DEALLOCATE PREPARE s1;
