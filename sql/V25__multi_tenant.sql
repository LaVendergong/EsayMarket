-- ============================================================================
-- 多租户：商户之间数据完全隔离（共享库共享表 + tenant_id）
--
-- 为什么用 tenant_id 而不是「一租户一库」：
--   演示/中小规模下，一租户一库的运维成本（建库、迁移 N 次、跨店统计要聚合）远大于收益；
--   tenant_id 方案靠 MyBatis-Plus 的 TenantLineInnerInterceptor 在 SQL 层强制注入条件，
--   业务代码很难漏（手动拼条件早晚会漏，那才是真正的事故）。
--
-- 隔离范围：商品/分类/SKU/订单/售后/评价/券/卡密/凭证/收藏/购物车/支付。
-- 不隔离：t_tenant 本身、t_user（买家全局，一个人在任何店都能买）、
--         后台的角色与权限、操作审计、搜索热词、refresh token。
--
-- 默认值 1 = 「默认店铺」，所以升级后现有全部数据自动归到 1 号租户，行为不变。
-- ============================================================================
USE mall;
SET NAMES utf8mb4;

-- ---------------------------------------------------------------- 租户表
CREATE TABLE IF NOT EXISTS t_tenant (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    code        VARCHAR(64) NOT NULL COMMENT '租户标识，子域名/路径用',
    name        VARCHAR(64) NOT NULL,
    contact     VARCHAR(64) DEFAULT NULL,
    status      TINYINT     NOT NULL DEFAULT 1 COMMENT '1 启用 0 停用',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='商户租户';

INSERT INTO t_tenant (id, code, name, contact, status, deleted)
VALUES (1, 'default', '默认店铺', 'platform', 1, 0)
ON DUPLICATE KEY UPDATE name = VALUES(name);

-- ---------------------------------------------------------------- 业务表加 tenant_id（幂等）
-- 每张表单独判断：MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，只能查 information_schema 再动态执行
SET @ddl = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_category ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1', 'SELECT 1')
    FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_category' AND column_name = 'tenant_id');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_product ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1', 'SELECT 1')
    FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_product' AND column_name = 'tenant_id');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_sku ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1', 'SELECT 1')
    FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_sku' AND column_name = 'tenant_id');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_order ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1', 'SELECT 1')
    FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_order' AND column_name = 'tenant_id');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_order_item ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1', 'SELECT 1')
    FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_order_item' AND column_name = 'tenant_id');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_after_sale ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1', 'SELECT 1')
    FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_after_sale' AND column_name = 'tenant_id');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_review ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1', 'SELECT 1')
    FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_review' AND column_name = 'tenant_id');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_coupon ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1', 'SELECT 1')
    FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_coupon' AND column_name = 'tenant_id');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_user_coupon ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1', 'SELECT 1')
    FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_user_coupon' AND column_name = 'tenant_id');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_freight_template ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1', 'SELECT 1')
    FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_freight_template' AND column_name = 'tenant_id');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_card_key ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1', 'SELECT 1')
    FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_card_key' AND column_name = 'tenant_id');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_digital_grant ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1', 'SELECT 1')
    FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_digital_grant' AND column_name = 'tenant_id');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_favorite ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1', 'SELECT 1')
    FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_favorite' AND column_name = 'tenant_id');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_cart ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1', 'SELECT 1')
    FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_cart' AND column_name = 'tenant_id');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_payment ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1', 'SELECT 1')
    FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_payment' AND column_name = 'tenant_id');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 管理员账号（也在 t_user）：0 = 平台方（可跨租户），>0 = 某商户的管理员
SET @ddl = (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE t_user ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT ''0=平台方，>0=商户管理员所绑定的租户''',
        'SELECT 1')
    FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_user' AND column_name = 'tenant_id');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 现有管理员都是平台方（能跨租户），保持升级前行为
UPDATE t_user SET tenant_id = 0 WHERE role = 1 AND tenant_id <> 0;

-- ---------------------------------------------------------------- 唯一键改成「租户内唯一」
-- 订单号/售后单号是应用生成的，不同店可能撞号；单号全局唯一是错的约束
SET @ddl = (SELECT IF(COUNT(*) > 0,
        'ALTER TABLE t_order DROP INDEX uk_order_no, ADD UNIQUE KEY uk_tenant_order_no (tenant_id, order_no)',
        'SELECT 1')
    FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 't_order' AND index_name = 'uk_order_no');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl = (SELECT IF(COUNT(*) > 0,
        'ALTER TABLE t_after_sale DROP INDEX uk_after_sale_no, ADD UNIQUE KEY uk_tenant_after_sale_no (tenant_id, after_sale_no)',
        'SELECT 1')
    FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 't_after_sale' AND index_name = 'uk_after_sale_no');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------------------------------------------------------------- 租户维度索引
SET @ddl = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_order ADD KEY idx_tenant (tenant_id, id)', 'SELECT 1')
    FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 't_order' AND index_name = 'idx_tenant');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE t_product ADD KEY idx_tenant (tenant_id, status, id)', 'SELECT 1')
    FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 't_product' AND index_name = 'idx_tenant');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
