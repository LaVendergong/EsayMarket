-- 商城 MVP 建表脚本（MySQL 8）
CREATE DATABASE IF NOT EXISTS mall DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE mall;

-- 用户
CREATE TABLE IF NOT EXISTS t_user (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    username     VARCHAR(64)  NOT NULL COMMENT '用户名',
    password     VARCHAR(128) NOT NULL COMMENT '密码(BCrypt)',
    nickname     VARCHAR(64)           DEFAULT NULL COMMENT '昵称',
    phone        VARCHAR(20)           DEFAULT NULL COMMENT '手机号',
    avatar       VARCHAR(255)          DEFAULT NULL COMMENT '头像',
    status       TINYINT      NOT NULL DEFAULT 1 COMMENT '0-禁用 1-正常',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB COMMENT ='用户';

-- 收货地址
CREATE TABLE IF NOT EXISTS t_address (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    user_id      BIGINT       NOT NULL COMMENT '用户ID',
    receiver     VARCHAR(64)  NOT NULL COMMENT '收货人',
    phone        VARCHAR(20)  NOT NULL COMMENT '电话',
    province     VARCHAR(64)           DEFAULT NULL,
    city         VARCHAR(64)           DEFAULT NULL,
    district     VARCHAR(64)           DEFAULT NULL,
    detail       VARCHAR(255) NOT NULL COMMENT '详细地址',
    is_default   TINYINT      NOT NULL DEFAULT 0 COMMENT '0-否 1-是',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_user (user_id)
) ENGINE = InnoDB COMMENT ='收货地址';

-- 商品分类
CREATE TABLE IF NOT EXISTS t_category (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    parent_id    BIGINT      NOT NULL DEFAULT 0 COMMENT '父级ID,0为顶级',
    name         VARCHAR(64) NOT NULL,
    sort         INT         NOT NULL DEFAULT 0,
    icon         VARCHAR(255)         DEFAULT NULL,
    status       TINYINT     NOT NULL DEFAULT 1,
    create_time  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted      TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_parent (parent_id)
) ENGINE = InnoDB COMMENT ='商品分类';

-- 商品 SPU
CREATE TABLE IF NOT EXISTS t_product (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    category_id  BIGINT        NOT NULL COMMENT '分类ID',
    name         VARCHAR(128)  NOT NULL COMMENT '商品名',
    sub_title    VARCHAR(255)           DEFAULT NULL COMMENT '副标题',
    main_image   VARCHAR(255)           DEFAULT NULL COMMENT '主图',
    images       TEXT                   DEFAULT NULL COMMENT '图集,逗号分隔',
    detail       LONGTEXT               DEFAULT NULL COMMENT '详情',
    price        DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '展示价(最低SKU价)',
    status       TINYINT       NOT NULL DEFAULT 1 COMMENT '0-下架 1-上架',
    sales        INT           NOT NULL DEFAULT 0 COMMENT '销量',
    create_time  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted      TINYINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_category (category_id),
    KEY idx_status (status)
) ENGINE = InnoDB COMMENT ='商品SPU';

-- 商品 SKU
CREATE TABLE IF NOT EXISTS t_sku (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    product_id   BIGINT        NOT NULL COMMENT 'SPU ID',
    sku_code     VARCHAR(64)            DEFAULT NULL COMMENT 'SKU编码',
    spec         VARCHAR(255)           DEFAULT NULL COMMENT '规格JSON,如 {"颜色":"红"}',
    image        VARCHAR(255)           DEFAULT NULL,
    price        DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    stock        INT           NOT NULL DEFAULT 0 COMMENT '库存',
    locked_stock INT           NOT NULL DEFAULT 0 COMMENT '锁定库存(下单未支付)',
    create_time  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted      TINYINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_product (product_id)
) ENGINE = InnoDB COMMENT ='商品SKU';

-- 购物车
CREATE TABLE IF NOT EXISTS t_cart (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    user_id      BIGINT      NOT NULL,
    product_id   BIGINT      NOT NULL,
    sku_id       BIGINT      NOT NULL,
    quantity     INT         NOT NULL DEFAULT 1,
    checked      TINYINT     NOT NULL DEFAULT 1 COMMENT '0-未选 1-选中',
    create_time  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted      TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_user (user_id),
    UNIQUE KEY uk_user_sku (user_id, sku_id, deleted)
) ENGINE = InnoDB COMMENT ='购物车';

-- 订单
CREATE TABLE IF NOT EXISTS t_order (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    order_no       VARCHAR(64)   NOT NULL COMMENT '订单号',
    user_id        BIGINT        NOT NULL,
    total_amount   DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '商品总额',
    pay_amount     DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '应付金额',
    status         TINYINT       NOT NULL DEFAULT 0 COMMENT '0-待支付 1-已支付 2-已取消 3-已完成',
    receiver       VARCHAR(64)            DEFAULT NULL,
    receiver_phone VARCHAR(20)            DEFAULT NULL,
    receiver_addr  VARCHAR(512)           DEFAULT NULL,
    pay_time       DATETIME               DEFAULT NULL,
    expire_time    DATETIME               DEFAULT NULL COMMENT '支付截止时间',
    create_time    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted        TINYINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_user (user_id),
    KEY idx_status (status)
) ENGINE = InnoDB COMMENT ='订单';

-- 订单明细
CREATE TABLE IF NOT EXISTS t_order_item (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    order_id      BIGINT        NOT NULL,
    order_no      VARCHAR(64)   NOT NULL,
    product_id    BIGINT        NOT NULL,
    sku_id        BIGINT        NOT NULL,
    product_name  VARCHAR(128)  NOT NULL,
    product_image VARCHAR(255)           DEFAULT NULL,
    spec          VARCHAR(255)           DEFAULT NULL,
    price         DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    quantity      INT           NOT NULL DEFAULT 1,
    total_amount  DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    create_time   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted       TINYINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_order (order_id)
) ENGINE = InnoDB COMMENT ='订单明细';
