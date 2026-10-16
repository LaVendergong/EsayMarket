-- ============================================================
-- P2 演示数据（幂等，可重复执行）
-- 用法：mysql -uroot -p*** --default-character-set=utf8mb4 -e "source D:/projects/mall/sql/V3__seed_demo.sql"
-- 说明：只写死演示数据；真实业务数据请走管理端接口。
--       管理员账号见本文件末尾（malladmin / admin123456）。
--       商品图用的是 /img/*.svg，这些文件在 backend/mall-core/src/main/resources/static/img/ 下，
--       两个应用都会从自己的端口把它们当静态资源对外提供（前端 dev 时走 vite 的 /img 代理）。
-- ============================================================
USE mall;

-- 分类：1/4 顶级，2/3 挂在 1 下，5 挂在 4 下
INSERT INTO t_category (id, parent_id, name, sort, icon, status, deleted) VALUES
  (1, 0, '数码',   1, NULL, 1, 0),
  (2, 1, '手机',   1, NULL, 1, 0),
  (3, 1, '笔记本', 2, NULL, 1, 0),
  (4, 0, '服饰',   2, NULL, 1, 0),
  (5, 4, '男装',   1, NULL, 1, 0)
ON DUPLICATE KEY UPDATE parent_id=VALUES(parent_id), name=VALUES(name), sort=VALUES(sort),
                        icon=VALUES(icon), status=VALUES(status), deleted=0;

-- 商品：1/2 在手机，3 在笔记本，4 在男装，5 为下架样本
INSERT INTO t_product (id, category_id, name, sub_title, main_image, images, detail, price, status, sales, deleted) VALUES
  (1, 2, 'iPhone 15 Pro',        'A17 Pro 芯片 / 钛金属边框', '/img/p1.svg', '/img/p1-1.svg,/img/p1-2.svg', '<p>iPhone 15 Pro 演示商品</p>', 7999.00,  1, 120, 0),
  (2, 2, '小米 14',              '骁龙 8 Gen3 / 徕卡光学',    '/img/p2.svg', '/img/p2-1.svg',                 '<p>小米 14 演示商品</p>',       3999.00,  1, 260, 0),
  (3, 3, 'ThinkPad X1 Carbon',   '14 英寸 / 1.12kg',          '/img/p3.svg', '/img/p3-1.svg',                 '<p>ThinkPad 演示商品</p>',      12999.00, 1, 35,  0),
  (4, 5, '男士休闲夹克',          '春秋薄款',                  '/img/p4.svg', '/img/p4-1.svg',                 '<p>男装演示商品</p>',           399.00,   1, 88,  0),
  (5, 2, '下架测试机',            '仅用于验证下架过滤',        '/img/p5.svg', NULL,                            '<p>下架样本</p>',               1999.00,  0, 0,   0)
ON DUPLICATE KEY UPDATE category_id=VALUES(category_id), name=VALUES(name), sub_title=VALUES(sub_title),
                        main_image=VALUES(main_image), images=VALUES(images), detail=VALUES(detail),
                        price=VALUES(price), status=VALUES(status), sales=VALUES(sales), deleted=0;

-- SKU：库存只存在 SKU 上，商品列表展示库存合计
INSERT INTO t_sku (id, product_id, sku_code, spec, image, price, stock, locked_stock, deleted) VALUES
  (1, 1, 'IP15P-256', '256G 原色钛金属', '/img/p1-1.svg', 7999.00,  100, 0, 0),
  (2, 1, 'IP15P-512', '512G 原色钛金属', '/img/p1-2.svg', 8999.00,  50,  0, 0),
  (3, 2, 'MI14-12-256', '12G+256G 黑色', '/img/p2-1.svg', 3999.00,  200, 0, 0),
  (4, 3, 'X1C-I7-16',  'i7/16G/512G',    '/img/p3-1.svg', 12999.00, 30,  0, 0),
  (5, 4, 'JK-M',       'M 码 卡其',       '/img/p4-1.svg', 399.00,   80,  0, 0),
  (6, 4, 'JK-L',       'L 码 卡其',       '/img/p4-1.svg', 399.00,   60,  0, 0),
  (7, 5, 'OFF-001',    '默认',           NULL,            1999.00,  1,   0, 0)
ON DUPLICATE KEY UPDATE product_id=VALUES(product_id), sku_code=VALUES(sku_code), spec=VALUES(spec),
                        image=VALUES(image), price=VALUES(price), stock=VALUES(stock), deleted=0;

-- 后台管理员账号：malladmin / admin123456（BCrypt）
INSERT INTO t_user (username, password, nickname, role, status, deleted) VALUES
  ('malladmin', '$2a$10$91wQJhjgVHh.lu.7eRF6weGY.4g15R6JyiAypTiWzUC6kJn38qyDe', '管理员', 1, 1, 0)
ON DUPLICATE KEY UPDATE password=VALUES(password), role=1, status=1, deleted=0;
