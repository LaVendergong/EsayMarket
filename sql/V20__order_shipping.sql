-- ============================================================================
-- 物流信息：发货时必须留下快递公司 + 单号
--
-- 之前 ship() 只把状态从「已支付」推到「已发货」，用户和客服都查不到任何物流内容。
-- 这里只做单号（不接第三方轨迹查询：快递100 之类要付费、也没有测试环境），
-- 已能覆盖「客服问一句、告诉用户单号」这个最刚需的场景。
--
-- ship_company 存枚举 code（SF/ZTO/...），便于后续按快递公司聚合。
-- ============================================================================
USE mall;
SET NAMES utf8mb4;

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE t_order
            ADD COLUMN ship_company VARCHAR(32) DEFAULT NULL COMMENT ''快递公司 code，见 ExpressCompany'',
            ADD COLUMN ship_no VARCHAR(64) DEFAULT NULL COMMENT ''运单号'',
            ADD COLUMN ship_time DATETIME DEFAULT NULL COMMENT ''发货时间''',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 't_order' AND column_name = 'ship_no'
);
PREPARE s1 FROM @ddl;
EXECUTE s1;
DEALLOCATE PREPARE s1;
