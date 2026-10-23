-- ============================================================================
-- 虚拟商品：卡密 / 外链（电子资料），无物流的"收货"链路
--
-- 与实物的三处本质差异：
--   1) 不需要收货地址（下单跳过地址校验）
--   2) 不存在"后台发货"：支付成功即发放，发放完直接置为已完成
--   3) 售后只可能是「仅退款」，且已查看过的卡密不允许退
--
-- 库存口径：卡密池就是库存来源。后台导入 N 张卡密时 sku.stock += N，
-- 下单仍走现有的 lockStock 防超卖，支付后从池子里条件 UPDATE 取一条（取不到=数据不一致，要告警）。
--
-- 字段语义：
--   t_product.product_type     1 实物 2 虚拟
--   t_product.delivery_type    虚拟时才有值：1 卡密 2 外链
--   t_product.delivery_url     外链类商品的地址（只做外链，不引入对象存储）
--   t_order.order_type         下单时按商品类型快照，避免商品改类型后历史订单错乱
-- ============================================================================
USE mall;
SET NAMES utf8mb4;

-- ---------------------------------------------------------------- 商品
SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE t_product
            ADD COLUMN product_type TINYINT NOT NULL DEFAULT 1 COMMENT ''1 实物 2 虚拟'',
            ADD COLUMN delivery_type TINYINT DEFAULT NULL COMMENT ''虚拟：1 卡密 2 外链'',
            ADD COLUMN delivery_url VARCHAR(512) DEFAULT NULL COMMENT ''外链类商品的下载地址''',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 't_product' AND column_name = 'product_type'
);
PREPARE s1 FROM @ddl;
EXECUTE s1;
DEALLOCATE PREPARE s1;

-- ---------------------------------------------------------------- 订单类型快照
SET @ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE t_order ADD COLUMN order_type TINYINT NOT NULL DEFAULT 1 COMMENT ''1 实物 2 虚拟（下单时快照）''',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 't_order' AND column_name = 'order_type'
);
PREPARE s2 FROM @ddl;
EXECUTE s2;
DEALLOCATE PREPARE s2;

-- ---------------------------------------------------------------- 卡密池
CREATE TABLE IF NOT EXISTS t_card_key (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    product_id    BIGINT       NOT NULL,
    sku_id        BIGINT       NOT NULL,
    -- 卡号/卡密都是 AES-256-GCM 密文（见 User.phone 的说明），长度比明文膨胀 2 倍多
    card_no       VARCHAR(512) NOT NULL COMMENT '卡号（密文）',
    card_secret   VARCHAR(512) NOT NULL COMMENT '卡密（密文）',
    status        TINYINT      NOT NULL DEFAULT 0 COMMENT '0 可用 1 已售出 2 已作废（退款/泄露）',
    order_id      BIGINT       DEFAULT NULL,
    order_item_id BIGINT       DEFAULT NULL,
    sold_time     DATETIME     DEFAULT NULL,
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted       TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- 发放时按「这个 SKU 的可用卡」抽一张，这个索引就是它的取数路径
    KEY idx_pick (sku_id, status, id),
    KEY idx_order (order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='卡密池';

-- ---------------------------------------------------------------- 发放凭证
CREATE TABLE IF NOT EXISTS t_digital_grant (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    order_id      BIGINT       NOT NULL,
    order_item_id BIGINT       NOT NULL,
    user_id       BIGINT       NOT NULL,
    product_id    BIGINT       NOT NULL,
    sku_id        BIGINT       NOT NULL,
    delivery_type TINYINT      NOT NULL COMMENT '1 卡密 2 外链',
    -- 卡密类：加密的 {"no":"...","secret":"..."}；外链类：加密的 {"url":"..."}
    content       VARCHAR(2048) NOT NULL COMMENT '发放内容（密文）',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1 已发放 2 已作废 9 发放失败（待人工）',
    view_count    INT          NOT NULL DEFAULT 0 COMMENT '查看次数：>0 说明卡密已暴露，售后不再自动退',
    grant_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted       TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- 一个订单项可能对应多条（买 3 张卡就是 3 条），所以不能建唯一键
    KEY idx_order_item (order_item_id),
    KEY idx_user (user_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='虚拟商品发放凭证';
