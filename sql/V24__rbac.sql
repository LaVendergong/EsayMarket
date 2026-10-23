-- ============================================================================
-- 后台 RBAC（用户 → 角色 → 权限码）
--
-- 模型取自 ruoyi 的「用户-角色-菜单/权限」三元关系，但**不搬它的实现**：
--   * 我们用「权限码」而不是 sys_menu：前端是静态路由，不需要存目录结构；
--   * 不做部门数据权限（@DataScope）：我们没有部门概念，硬加纯负担；
--   * 登录链路保持现有的 HttpOnly Cookie + refresh token，不换成 ruoyi 那套 TokenService。
--
-- 权限码命名与 ruoyi 一致：模块:动作（product:edit / order:ship / after-sale:handle）。
-- 超级管理员（SUPER_ADMIN）在代码里短路为「拥有全部权限」，不依赖 t_admin_role_permission，
-- 避免以后加权限点还要回来补关联。
-- ============================================================================
USE mall;
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS t_admin_role (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    code        VARCHAR(64) NOT NULL COMMENT '角色标识，如 SUPER_ADMIN',
    name        VARCHAR(64) NOT NULL,
    description VARCHAR(200) DEFAULT NULL,
    /** 1 = 内置角色，不允许删除 */
    built_in    TINYINT     NOT NULL DEFAULT 0,
    status      TINYINT     NOT NULL DEFAULT 1 COMMENT '1 启用 0 停用',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='后台角色';

CREATE TABLE IF NOT EXISTS t_admin_permission (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    code       VARCHAR(64) NOT NULL COMMENT '权限码，如 order:ship',
    name       VARCHAR(64) NOT NULL,
    perm_group VARCHAR(32) NOT NULL COMMENT '分组（菜单维度）',
    sort       INT         NOT NULL DEFAULT 0,
    create_time DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 实体继承 BaseEntity，会按 update_time 写库；漏了这一列查询直接报 Unknown column
    update_time DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted    TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='后台权限点';

CREATE TABLE IF NOT EXISTS t_admin_user_role (
    id      BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_role (user_id, role_id),
    KEY idx_role (role_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='管理员-角色';

CREATE TABLE IF NOT EXISTS t_admin_role_permission (
    id            BIGINT NOT NULL AUTO_INCREMENT,
    role_id       BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_perm (role_id, permission_id),
    KEY idx_perm (permission_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='角色-权限';

-- ---------------------------------------------------------------- 权限点
INSERT INTO t_admin_permission (code, name, perm_group, sort) VALUES
  ('dashboard:view',    '查看首页看板',   '首页',   1),
  ('report:view',       '查看销售报表',   '报表',   2),
  ('product:view',      '查看商品',       '商品',   10),
  ('product:edit',      '编辑商品',       '商品',   11),
  ('product:delete',    '删除商品',       '商品',   12),
  ('category:view',     '查看分类',       '分类',   20),
  ('category:edit',     '编辑分类',       '分类',   21),
  ('order:view',        '查看订单',       '订单',   30),
  ('order:ship',        '订单发货',       '订单',   31),
  ('order:close',       '关闭订单',       '订单',   32),
  ('order:grant',       '补发虚拟凭证',   '订单',   33),
  ('order:export',      '导出订单',       '订单',   34),
  ('after-sale:view',   '查看售后',       '售后',   40),
  ('after-sale:handle', '处理售后',       '售后',   41),
  ('review:view',       '查看评价',       '评价',   50),
  ('review:handle',     '隐藏/恢复评价',  '评价',   51),
  ('coupon:view',       '查看优惠券',     '优惠券', 60),
  ('coupon:edit',       '编辑优惠券',     '优惠券', 61),
  ('freight:view',      '查看运费模板',   '运费',   70),
  ('freight:edit',      '编辑运费模板',   '运费',   71),
  ('card-key:import',   '导入卡密',       '卡密',   80),
  ('audit:view',        '查看操作审计',   '系统',   90),
  ('maintenance:view',  '查看数据维护',   '系统',   91),
  ('system:rbac',       '管理角色与授权', '系统',   92)
ON DUPLICATE KEY UPDATE name = VALUES(name), perm_group = VALUES(perm_group), sort = VALUES(sort);

-- ---------------------------------------------------------------- 角色
INSERT INTO t_admin_role (code, name, description, built_in, status) VALUES
  ('SUPER_ADMIN', '超级管理员', '拥有全部权限，内置角色不可删除', 1, 1),
  ('OPERATOR',    '运营',       '商品/订单/售后/评价，不能改优惠券和运费', 0, 1),
  ('FINANCE',     '财务',       '订单与售后查看、导出、报表，不能改商品', 0, 1)
ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), built_in = VALUES(built_in);

-- 运营：除了优惠券/运费/系统设置，其余都能干
INSERT INTO t_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM t_admin_role r JOIN t_admin_permission p
WHERE r.code = 'OPERATOR' AND p.code IN (
    'dashboard:view', 'report:view',
    'product:view', 'product:edit', 'product:delete',
    'category:view', 'category:edit',
    'order:view', 'order:ship', 'order:close', 'order:grant', 'order:export',
    'after-sale:view', 'after-sale:handle',
    'review:view', 'review:handle',
    'card-key:import'
)
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);

-- 财务：只看和钱相关的
INSERT INTO t_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM t_admin_role r JOIN t_admin_permission p
WHERE r.code = 'FINANCE' AND p.code IN (
    'dashboard:view', 'report:view',
    'order:view', 'order:export',
    'after-sale:view', 'after-sale:handle'
)
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);

-- 现有管理员默认给超级管理员，升级后行为不变
INSERT INTO t_admin_user_role (user_id, role_id)
SELECT u.id, r.id FROM t_user u JOIN t_admin_role r
WHERE u.username = 'malladmin' AND u.deleted = 0 AND r.code = 'SUPER_ADMIN'
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);
