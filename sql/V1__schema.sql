-- ============================================================
-- V1：建库建表（8 张表）
-- 用法：mysql -uroot -p*** --default-character-set=utf8mb4 -e "source D:/projects/mall/sql/V1__schema.sql"
--
-- 为什么叫 V1__schema.sql 而不是 schema.sql：
--   docker-entrypoint-initdb.d 是按**文件名顺序**执行 *.sql 的，而 ASCII 里大写 V(86) < 小写 s(115)，
--   叫 schema.sql 会被排到 V2/V3/V4 之后执行，建表顺序就反了。统一 V<N>__ 前缀后顺序天然正确。
-- ============================================================
-- 显式声明本文件是 UTF-8：initdb 用的 mysql 客户端可能默认 latin1，
-- 那样中文会被二次编码存进库（表面看是乱码，实际是数据写坏了）。见 docs §8 第 68 条。
SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS mall DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE mall;

CREATE TABLE IF NOT EXISTS t_user (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    password VARCHAR(128) NOT NULL,
    nickname VARCHAR(64) DEFAULT NULL,
    phone VARCHAR(128) DEFAULT NULL COMMENT '手机号（AES-GCM 密文，见 V6）',
    avatar VARCHAR(255) DEFAULT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB COMMENT ='用户';

CREATE TABLE IF NOT EXISTS t_address (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    receiver VARCHAR(256) NOT NULL COMMENT '收货人（密文）',
    phone VARCHAR(128) NOT NULL COMMENT '联系电话（密文）',
    province VARCHAR(64) DEFAULT NULL,
    city VARCHAR(64) DEFAULT NULL,
    district VARCHAR(64) DEFAULT NULL,
    detail VARCHAR(1024) NOT NULL COMMENT '详细门牌（密文）',
    is_default TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_user (user_id)
) ENGINE = InnoDB COMMENT ='收货地址';

CREATE TABLE IF NOT EXISTS t_category (
    id BIGINT NOT NULL AUTO_INCREMENT,
    parent_id BIGINT NOT NULL DEFAULT 0,
    name VARCHAR(64) NOT NULL,
    sort INT NOT NULL DEFAULT 0,
    icon VARCHAR(255) DEFAULT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_parent (parent_id)
) ENGINE = InnoDB COMMENT ='商品分类';

CREATE TABLE IF NOT EXISTS t_product (
    id BIGINT NOT NULL AUTO_INCREMENT,
    category_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    sub_title VARCHAR(255) DEFAULT NULL,
    main_image VARCHAR(255) DEFAULT NULL,
    images TEXT DEFAULT NULL,
    detail LONGTEXT DEFAULT NULL,
    price DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    status TINYINT NOT NULL DEFAULT 1,
    sales INT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_category (category_id),
    KEY idx_status (status)
) ENGINE = InnoDB COMMENT ='商品SPU';

CREATE TABLE IF NOT EXISTS t_sku (
    id BIGINT NOT NULL AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    sku_code VARCHAR(64) DEFAULT NULL,
    spec VARCHAR(255) DEFAULT NULL,
    image VARCHAR(255) DEFAULT NULL,
    price DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    stock INT NOT NULL DEFAULT 0,
    locked_stock INT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_product (product_id)
) ENGINE = InnoDB COMMENT ='商品SKU';

CREATE TABLE IF NOT EXISTS t_cart (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    quantity INT NOT NULL DEFAULT 1,
    checked TINYINT NOT NULL DEFAULT 1,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_user (user_id),
    UNIQUE KEY uk_user_sku (user_id, sku_id, deleted)
) ENGINE = InnoDB COMMENT ='购物车';

CREATE TABLE IF NOT EXISTS t_order (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_no VARCHAR(64) NOT NULL,
    request_id VARCHAR(64) DEFAULT NULL COMMENT '下单幂等键（客户端生成，见 V5__order_request_id.sql）',
    user_id BIGINT NOT NULL,
    total_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    pay_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    status TINYINT NOT NULL DEFAULT 0,
    receiver VARCHAR(256) DEFAULT NULL COMMENT '收货人快照（密文）',
    receiver_phone VARCHAR(128) DEFAULT NULL COMMENT '电话快照（密文）',
    receiver_addr VARCHAR(2048) DEFAULT NULL COMMENT '地址快照（密文）',
    pay_time DATETIME DEFAULT NULL,
    expire_time DATETIME DEFAULT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    UNIQUE KEY uk_user_request (user_id, request_id),
    KEY idx_user (user_id),
    KEY idx_status (status)
) ENGINE = InnoDB COMMENT ='订单';

CREATE TABLE IF NOT EXISTS t_order_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    product_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    product_name VARCHAR(128) NOT NULL,
    product_image VARCHAR(255) DEFAULT NULL,
    spec VARCHAR(255) DEFAULT NULL,
    price DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    quantity INT NOT NULL DEFAULT 1,
    total_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_order (order_id)
) ENGINE = InnoDB COMMENT ='订单明细';
