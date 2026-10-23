-- ============================================================================
-- 分类库：从 5 条演示分类补成一套可用的商城分类（4 个顶级 / 12 个叶子）
--
-- 为什么现在做：搜索要按分类筛、要有关键词可命中，而原来只有
-- 数码(手机/笔记本) + 服饰(男装) 三条叶子分类，且库里有几十条历史测试分类
-- （UI分类改名xxx、Pay Cat xxx、AS Cat xxx…）会直接出现在前台分类树里。
--
-- 原则：
--   1) 只保留 2 级（顶级 → 叶子）。3 级对演示商城没收益，前台 tab 也放不下。
--   2) 每个叶子分类至少 1 个商品 —— 空分类在前台就是一个点进去没东西的坑。
--   3) 卡密类商品库存置 0：卡密必须由后台导入（导入多少张就加多少库存），
--      种子数据不能直接插卡密（内容是 AES-GCM 密文，SQL 里写不了明文）。
--   4) 幂等：可重复执行。
-- ============================================================================
USE mall;
SET NAMES utf8mb4;

-- ---------------------------------------------------------------- 清理历史测试数据
-- 正经分类只有 1-5（本文件之前建的都在下面重新插入）；其余都是历次验证脚本留下的
UPDATE t_category SET deleted = 1 WHERE deleted = 0 AND id NOT IN (1, 2, 3, 4, 5);

-- 测试商品（CardKey-xxx / LinkItem-xxx / UI商品xxx）一并下线，历史订单有快照不受影响。
-- 只清理 id < 300 的：300+ 是下面补的正经商品，重复执行时不能被误删
UPDATE t_product SET deleted = 1 WHERE deleted = 0 AND id < 300 AND id NOT IN (1, 2, 3, 4, 5);
UPDATE t_sku SET deleted = 1 WHERE deleted = 0
    AND product_id NOT IN (SELECT id FROM (SELECT id FROM t_product WHERE deleted = 0) p);
UPDATE t_card_key SET deleted = 1 WHERE deleted = 0
    AND sku_id NOT IN (SELECT id FROM (SELECT id FROM t_sku WHERE deleted = 0) s);

-- ---------------------------------------------------------------- 分类
INSERT INTO t_category (id, parent_id, name, sort, icon, status, deleted) VALUES
  (1,  0, '数码',        1, NULL, 1, 0),
  (2,  1, '手机',        1, NULL, 1, 0),
  (3,  1, '笔记本',      2, NULL, 1, 0),
  (6,  1, '平板与穿戴',  3, NULL, 1, 0),
  (7,  1, '影音配件',    4, NULL, 1, 0),
  (14, 0, '家用电器',    2, NULL, 1, 0),
  (16, 14, '厨房电器',   1, NULL, 1, 0),
  (17, 14, '生活电器',   2, NULL, 1, 0),
  (4,  0, '服饰',        3, NULL, 1, 0),
  (5,  4, '男装',        1, NULL, 1, 0),
  (8,  4, '女装',        2, NULL, 1, 0),
  (9,  4, '鞋靴箱包',    3, NULL, 1, 0),
  (10, 0, '虚拟商品',    4, NULL, 1, 0),
  (11, 10, '卡密充值',   1, NULL, 1, 0),
  (12, 10, '电子资料',   2, NULL, 1, 0),
  (13, 10, '软件授权',   3, NULL, 1, 0)
ON DUPLICATE KEY UPDATE parent_id = VALUES(parent_id), name = VALUES(name), sort = VALUES(sort),
                        status = VALUES(status), deleted = 0;

-- ---------------------------------------------------------------- 商品
-- 现有 5 个演示商品（1-5）分类保持不动；下面是补的 19 个，覆盖每个叶子分类
INSERT INTO t_product (id, category_id, name, sub_title, main_image, images, detail, price,
                       status, sales, freight_template_id, product_type, delivery_type, delivery_url, deleted) VALUES
  (300, 2,  'iPhone 16 Pro',      'A18 Pro / 钛金属边框',      '/img/p1.svg', '/img/p1-1.svg', '<p>iPhone 16 Pro 演示商品</p>',       8999.00, 1, 30,  2, 1, NULL, NULL, 0),
  (301, 2,  '华为 Mate 70',       '麒麟芯片 / 卫星通信',        '/img/p2.svg', '/img/p2-1.svg', '<p>华为 Mate 70 演示商品</p>',        5499.00, 1, 55,  2, 1, NULL, NULL, 0),
  (302, 3,  'MacBook Air M3',     '13 英寸 / 16G+512G',        '/img/p3.svg', '/img/p3-1.svg', '<p>MacBook Air 演示商品</p>',         8999.00, 1, 20,  2, 1, NULL, NULL, 0),
  (303, 6,  'iPad Air 11',        'M2 芯片 / 11 英寸',          '/img/p1.svg', '/img/p1-1.svg', '<p>iPad Air 演示商品</p>',            4799.00, 1, 40,  2, 1, NULL, NULL, 0),
  (304, 6,  'Apple Watch S10',    '46mm GPS 版',               '/img/p2.svg', '/img/p2-1.svg', '<p>Apple Watch 演示商品</p>',         2999.00, 1, 25,  2, 1, NULL, NULL, 0),
  (305, 7,  'AirPods Pro 2',      '主动降噪 / USB-C',          '/img/p1.svg', '/img/p1-1.svg', '<p>AirPods Pro 演示商品</p>',         1899.00, 1, 80,  2, 1, NULL, NULL, 0),
  (306, 7,  '索尼 WH-1000XM5',    '头戴式降噪耳机',             '/img/p2.svg', '/img/p2-1.svg', '<p>索尼降噪耳机演示商品</p>',          2399.00, 1, 15,  2, 1, NULL, NULL, 0),
  (307, 16, '米家 IH 电饭煲',     '4L / 压力 IH',              '/img/p3.svg', '/img/p3-1.svg', '<p>米家电饭煲演示商品</p>',            399.00, 1, 120, 2, 1, NULL, NULL, 0),
  (308, 16, '空气炸锅 5L',        '可视窗口 / 无油低脂',        '/img/p4.svg', '/img/p4-1.svg', '<p>空气炸锅演示商品</p>',              299.00, 1, 200, 2, 1, NULL, NULL, 0),
  (309, 17, '石头扫地机器人 P10', '扫拖一体 / 自动集尘',        '/img/p3.svg', '/img/p3-1.svg', '<p>扫地机器人演示商品</p>',           1999.00, 1, 35,  2, 1, NULL, NULL, 0),
  (310, 17, '飞利浦电动牙刷',     '声波震动 / 3 档模式',        '/img/p4.svg', '/img/p4-1.svg', '<p>电动牙刷演示商品</p>',              349.00, 1, 150, 2, 1, NULL, NULL, 0),
  (311, 8,  '女士风衣',           '春秋款 / 中长款',            '/img/p4.svg', '/img/p4-1.svg', '<p>女士风衣演示商品</p>',              599.00, 1, 45,  2, 1, NULL, NULL, 0),
  (312, 9,  '轻量跑步鞋',         '缓震回弹 / 透气网面',        '/img/p5.svg', '/img/p5-1.svg', '<p>跑步鞋演示商品</p>',                499.00, 1, 90,  2, 1, NULL, NULL, 0),
  (313, 9,  '简约双肩背包',       '15.6 英寸电脑仓',            '/img/p5.svg', '/img/p5-1.svg', '<p>双肩包演示商品</p>',                259.00, 1, 110, 2, 1, NULL, NULL, 0),
  -- 虚拟商品：卡密类库存 0（必须后台导入卡密后自动加库存）
  (314, 11, 'Steam 充值卡 100 元', '国区可用 / 即时到账',        '/img/p1.svg', NULL,           '<p>付款后自动发放卡号与卡密</p>',      100.00, 1, 0,   NULL, 2, 1, NULL, 0),
  (315, 11, '视频会员月卡',        '30 天 / 全平台',             '/img/p2.svg', NULL,           '<p>付款后自动发放激活码</p>',           25.00, 1, 0,   NULL, 2, 1, NULL, 0),
  (316, 12, 'Java 并发编程实战 PDF', '电子书 / 永久下载',        '/img/p3.svg', NULL,           '<p>付款后发放下载链接</p>',             39.00, 1, 500, NULL, 2, 2, 'https://example.com/download/java-concurrency.pdf', 0),
  (317, 12, '电商设计素材包',      '200+ PSD / 可商用',          '/img/p4.svg', NULL,           '<p>付款后发放下载链接</p>',             59.00, 1, 300, NULL, 2, 2, 'https://example.com/download/design-kit.zip', 0),
  (318, 13, 'IDE 一年授权码',      '支持 3 台设备',              '/img/p5.svg', NULL,           '<p>付款后发放授权码</p>',              299.00, 1, 0,   NULL, 2, 1, NULL, 0)
ON DUPLICATE KEY UPDATE category_id = VALUES(category_id), name = VALUES(name),
                        sub_title = VALUES(sub_title), main_image = VALUES(main_image),
                        images = VALUES(images), detail = VALUES(detail), price = VALUES(price),
                        status = VALUES(status), sales = VALUES(sales),
                        freight_template_id = VALUES(freight_template_id),
                        product_type = VALUES(product_type), delivery_type = VALUES(delivery_type),
                        delivery_url = VALUES(delivery_url), deleted = 0;

-- ---------------------------------------------------------------- SKU
INSERT INTO t_sku (id, product_id, sku_code, spec, image, price, stock, locked_stock, deleted) VALUES
  (400, 300, 'IP16P-256',   '256G 黑色钛',  '/img/p1-1.svg', 8999.00, 60,  0, 0),
  (401, 300, 'IP16P-512',   '512G 黑色钛',  '/img/p1-1.svg', 10299.00, 30, 0, 0),
  (402, 301, 'MATE70-256',  '12G+256G',    '/img/p2-1.svg', 5499.00, 80,  0, 0),
  (403, 302, 'MBA-M3-512',  '16G+512G',    '/img/p3-1.svg', 8999.00, 25,  0, 0),
  (404, 303, 'IPADAIR-128', '128G WiFi',   '/img/p1-1.svg', 4799.00, 40,  0, 0),
  (405, 304, 'AWS10-46',    '46mm GPS',    '/img/p2-1.svg', 2999.00, 35,  0, 0),
  (406, 305, 'AIRPODS-P2',  'USB-C',       '/img/p1-1.svg', 1899.00, 100, 0, 0),
  (407, 306, 'XM5-BLACK',   '黑色',         '/img/p2-1.svg', 2399.00, 20,  0, 0),
  (408, 307, 'MI-IH-4L',    '4L',          '/img/p3-1.svg', 399.00,  150, 0, 0),
  (409, 308, 'AF-5L',       '5L 可视',      '/img/p4-1.svg', 299.00,  220, 0, 0),
  (410, 309, 'P10-WHITE',   '白色',         '/img/p3-1.svg', 1999.00, 45,  0, 0),
  (411, 310, 'PHILIPS-HX',  '标准装',       '/img/p4-1.svg', 349.00,  180, 0, 0),
  (412, 311, 'COAT-M',      'M 码 卡其',    '/img/p4-1.svg', 599.00,  50,  0, 0),
  (413, 311, 'COAT-L',      'L 码 卡其',    '/img/p4-1.svg', 599.00,  40,  0, 0),
  (414, 312, 'RUN-42',      '42 码',       '/img/p5-1.svg', 499.00,  60,  0, 0),
  (415, 313, 'BAG-15',      '15.6 英寸',    '/img/p5-1.svg', 259.00,  120, 0, 0),
  (416, 314, 'STEAM-100',   '国区',         '/img/p1.svg',   100.00,  0,   0, 0),
  (417, 315, 'VIPA-MONTH',  '月卡',         '/img/p2.svg',   25.00,   0,   0, 0),
  (418, 316, 'PDF-JAVA',    'PDF',         '/img/p3.svg',   39.00,   999, 0, 0),
  (419, 317, 'KIT-DESIGN',  '素材包',       '/img/p4.svg',   59.00,   999, 0, 0),
  (420, 318, 'IDE-1Y',      '一年授权',     '/img/p5.svg',   299.00,  0,   0, 0)
-- 注意：ON DUPLICATE 里**不更新 stock**。卡密类 SKU 的库存由「导入卡密」驱动，
-- 重复执行种子脚本把 stock 重置回 0 会让库存与卡密池脱钩（对账任务会直接报不一致）
ON DUPLICATE KEY UPDATE product_id = VALUES(product_id), sku_code = VALUES(sku_code),
                        spec = VALUES(spec), image = VALUES(image), price = VALUES(price),
                        deleted = 0;
