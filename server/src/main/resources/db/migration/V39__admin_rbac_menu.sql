-- =====================================================================
-- V39：授权中心重构 —— 后台账号 / 角色 / 菜单权限（RBAC）落地
--
-- 背景（2026-09-25 需求）：
--   现有「授权中心」三个菜单与小程序的「经营角色授权」混在一起，语义错位：
--     · 「微信账号绑定」实际在写 app_user + user_role_grant（小程序用户），
--       与「可登录运营后台的账号」毫无关系；
--     · 「角色与权限」只有 编码/名称/数据范围 三个字段，没有任何菜单权限分配；
--     · sys_menu 表建了但从未使用（无种子数据、无 role_menu 关联表），
--       后端 /route/getUserRoutes 恒返回空数组 —— 菜单权限链路根本没通。
--
-- 目标模型（账号 -> 角色 -> 菜单）：
--    sys_user ── sys_user_role ── sys_role ── sys_role_menu ── sys_menu
--
-- 本次改动：
--   1) 新建 sys_role_menu（角色-菜单关联）
--   2) sys_role 增加 is_builtin（内置角色，当前仅 R_SUPER）
--   3) sys_user 增加 is_super（超管账号标记，用于「不可改删」强约束）
--   4) sys_menu 增加 code / feature_flag，并补种子数据（与前端路由一一对应）
--   5) 按「内置角色默认菜单」回填 sys_role_menu（保持现有账号可见范围不变）
--   6) 超管唯一性保护：若已存在多个 R_SUPER 绑定，仅保留最早创建的账号
--
-- 【幂等设计】
--   · DDL 逐列/逐索引用 information_schema 判断后再执行，可重复运行；
--   · 种子数据以 code 唯一键 ON DUPLICATE KEY UPDATE 对齐；
--   · 回填 sys_role_menu 用 NOT EXISTS 去重。
--   刻意不手工插入 Flyway 历史记录 —— 迁移必须由 Flyway 自行执行并记录
--   （V31 曾因手工伪造 checksum 导致服务无法启动）。
-- =====================================================================

-- ---------- 1. 角色-菜单关联表 ----------
CREATE TABLE IF NOT EXISTS sys_role_menu (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    role_id     BIGINT UNSIGNED NOT NULL,
    menu_id     BIGINT UNSIGNED NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_role_menu (role_id, menu_id),
    KEY idx_sys_role_menu_role (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='后台角色-菜单';

-- ---------- 2. sys_role.is_builtin ----------
-- 内置角色：不可改名、不可改码、不可删除（当前仅 R_SUPER）。
-- 为什么用列而不是代码里写死角色码：后续若要新增「内置角色」无需再改代码，
-- 且列表页可直接据此禁用操作按钮。
SET @sql = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_role' AND COLUMN_NAME = 'is_builtin') = 0,
    'ALTER TABLE sys_role ADD COLUMN is_builtin TINYINT NOT NULL DEFAULT 0 COMMENT ''1内置角色不可改删''',
    'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE sys_role SET is_builtin = 1 WHERE code = 'R_SUPER' AND deleted = 0;

-- ---------- 3. sys_user.is_super ----------
-- 超管账号标记。存在的意义：
--   a) 「超级管理员只能有一个账号」的唯一性约束需要在应用层做前置校验，
--      有了该列可以避免每次 join sys_user_role / sys_role；
--   b) 账号列表需要一眼区分超管行，前端据此隐藏 停用/删除/改角色 按钮。
SET @sql = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'is_super') = 0,
    'ALTER TABLE sys_user ADD COLUMN is_super TINYINT NOT NULL DEFAULT 0 COMMENT ''1超级管理员账号''',
    'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 4. sys_menu 补列：code / feature_flag ----------
-- code：与前端路由 name 一一对应（如 auth_role），是前后端对齐菜单的唯一锚点。
--       用 code 而非 id 做锚点，避免迁移顺序变化导致 id 漂移。
-- feature_flag：对应前端路由 meta.featureFlag，动态菜单模式下需要一并下发，
--       否则营销类菜单（依赖功能开关）在客户端会被误判为未开启。
SET @sql = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_menu' AND COLUMN_NAME = 'code') = 0,
    'ALTER TABLE sys_menu ADD COLUMN code VARCHAR(64) NULL COMMENT ''前端路由 name，前后端对齐锚点''',
    'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_menu' AND COLUMN_NAME = 'feature_flag') = 0,
    'ALTER TABLE sys_menu ADD COLUMN feature_flag VARCHAR(64) NULL COMMENT ''功能开关编码，空表示无条件可见''',
    'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_menu' AND INDEX_NAME = 'uk_sys_menu_code') = 0,
    'ALTER TABLE sys_menu ADD UNIQUE KEY uk_sys_menu_code (code)',
    'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 5. sys_menu 种子数据 ----------
-- 与 src/router/elegant/routes.ts 的非 constant 路由一一对应。
-- parent_code 用 code 而不是 id：先插全部节点，再用一次 JOIN 关联父子，
-- 这样不依赖 AUTO_INCREMENT 的具体取值，迁移可重复执行且顺序无关。
--
-- 说明：name 列存中文标题（后台当前为纯中文界面），code 存路由 name。
CREATE TEMPORARY TABLE tmp_menu_seed (
    code         VARCHAR(64) NOT NULL,
    parent_code  VARCHAR(64) NULL,
    title        VARCHAR(64) NOT NULL,
    path         VARCHAR(128) NULL,
    component    VARCHAR(128) NULL,
    icon         VARCHAR(64) NULL,
    order_num    INT NOT NULL DEFAULT 0,
    hide_flag    TINYINT NOT NULL DEFAULT 0,
    feature_flag VARCHAR(64) NULL,
    PRIMARY KEY (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO tmp_menu_seed
    (code, parent_code, title, path, component, icon, order_num, hide_flag, feature_flag)
VALUES
('auth', NULL, '授权中心', '/auth', 'layout.base', 'mdi:shield-account', 20, 0, NULL),
('finance', NULL, '财务中心', '/finance', 'layout.base', 'mdi:wallet', 50, 0, NULL),
('home', NULL, 'home', '/home', 'layout.base$view.home', 'mdi:monitor-dashboard', 1, 0, NULL),
('marketing', NULL, '营销中心', '/marketing', 'layout.base', 'mdi:bullhorn', 45, 0, NULL),
('product', NULL, '商品中心', '/product', 'layout.base', 'mdi:cup-outline', 30, 0, NULL),
('review', NULL, '申请审核', '/review', 'layout.base', 'mdi:clipboard-text-clock', 25, 0, NULL),
('subject', NULL, '主体管理', '/subject', 'layout.base', 'mdi:domain', 10, 0, NULL),
('system', NULL, '系统审计', '/system', 'layout.base', 'mdi:shield', 60, 0, NULL),
('trade', NULL, '交易中心', '/trade', 'layout.base', 'mdi:receipt-text', 40, 0, NULL),
('user', NULL, '用户管理', '/user', 'layout.base', 'mdi:account-group', 15, 0, NULL),
('auth_account', 'auth', '账号管理', '/auth/account', 'view.auth_account', 'mdi:account-cog', 2, 0, NULL),
('auth_grant', 'auth', '角色授权记录', '/auth/grant', 'view.auth_grant', 'mdi:clipboard-list', 3, 0, NULL),
('auth_role', 'auth', '角色与权限', '/auth/role', 'view.auth_role', 'mdi:shield-key', 1, 0, NULL),
('finance_account', 'finance', '经营方账户', '/finance/account', 'view.finance_account', 'mdi:wallet-outline', 2, 0, NULL),
('finance_flow', 'finance', '资金流水', '/finance/flow', 'view.finance_flow', 'mdi:swap-horizontal', 3, 0, NULL),
('finance_pool', 'finance', '资金池', '/finance/pool', 'view.finance_pool', 'mdi:database', 1, 0, NULL),
('finance_reconcile', 'finance', '对账异常池', '/finance/reconcile', 'view.finance_reconcile', 'mdi:alert-circle', 4, 0, NULL),
('finance_snapshot', 'finance', '分账快照', '/finance/snapshot', 'view.finance_snapshot', 'mdi:camera', 1, 0, NULL),
('finance_snapshot-detail', 'finance', '分账快照详情', '/finance/snapshot-detail', 'view.finance_snapshot-detail', NULL, 0, 1, NULL),
('finance_withdraw', 'finance', '提现管理', '/finance/withdraw', 'view.finance_withdraw', 'mdi:cash', 5, 0, NULL),
('marketing_comment', 'marketing', '评论审核', '/marketing/comment', 'view.marketing_comment', 'mdi:comment', 6, 0, 'ENABLE_REVIEW'),
('marketing_coupon', 'marketing', '优惠券管理', '/marketing/coupon', 'view.marketing_coupon', 'mdi:ticket-percent', 1, 0, 'ENABLE_COUPON'),
('marketing_exchange', 'marketing', '兑换记录', '/marketing/exchange', 'view.marketing_exchange', 'mdi:swap-horizontal-bold', 8, 0, 'ENABLE_STORED_VALUE'),
('marketing_gift', 'marketing', '礼品卡', '/marketing/gift', 'view.marketing_gift', 'mdi:credit-card-outline', 3, 0, 'ENABLE_STORED_VALUE'),
('marketing_gift-order', 'marketing', '礼品卡订单', '/marketing/gift-order', 'view.marketing_gift-order', 'mdi:gift-open', 7, 0, 'ENABLE_STORED_VALUE'),
('marketing_member', 'marketing', '会员等级', '/marketing/member', 'view.marketing_member', 'mdi:account-star', 5, 0, 'ENABLE_STORED_VALUE'),
('marketing_points', 'marketing', '积分商城', '/marketing/points', 'view.marketing_points', 'mdi:star', 4, 0, 'ENABLE_STORED_VALUE'),
('marketing_points-rule', 'marketing', 'marketing_points-rule', '/marketing/points-rule', 'view.marketing_points-rule', NULL, 0, 0, NULL),
('marketing_referral', 'marketing', '分享有礼', '/marketing/referral', 'view.marketing_referral', 'mdi:share-variant', 9, 0, NULL),
('marketing_stored', 'marketing', '储值套餐', '/marketing/stored', 'view.marketing_stored', 'mdi:gift', 2, 0, 'ENABLE_STORED_VALUE'),
('product_category', 'product', '分类管理', '/product/category', 'view.product_category', 'mdi:shape', 1, 0, NULL),
('product_list', 'product', '商品管理', '/product/list', 'view.product_list', 'mdi:cup', 1, 0, NULL),
('product_split', 'product', '分账规则', '/product/split', 'view.product_split', 'mdi:percent', 3, 0, NULL),
('review_role', 'review', '角色开通审核', '/review/role', 'view.review_role', 'mdi:clipboard-check', 1, 0, NULL),
('review_role-detail', 'review', '角色开通审核详情', '/review/role-detail', 'view.review_role-detail', NULL, 0, 1, NULL),
('subject_channel', 'subject', '资源方管理', '/subject/channel', 'view.subject_channel', 'mdi:share-variant', 3, 0, NULL),
('subject_investor', 'subject', '投资人管理', '/subject/investor', 'view.subject_investor', 'mdi:account-tie', 4, 0, NULL),
('subject_platform', 'subject', '平台主体', '/subject/platform', 'view.subject_platform', 'mdi:office-building', 1, 0, NULL),
('subject_store', 'subject', '门店管理', '/subject/store', 'view.subject_store', 'mdi:store', 2, 0, NULL),
('subject_supplier', 'subject', '供应商管理', '/subject/supplier', 'view.subject_supplier', 'mdi:factory', 5, 0, NULL),
('system_audit', 'system', '审计日志', '/system/audit', 'view.system_audit', 'mdi:file-document', 1, 0, NULL),
('system_city', 'system', '城市管理', '/system/city', 'view.system_city', 'mdi:map-marker', 4, 0, NULL),
('system_config', 'system', '运营配置', '/system/config', 'view.system_config', 'mdi:cog', 5, 0, NULL),
('system_dict', 'system', '数据字典', '/system/dict', 'view.system_dict', 'mdi:book', 3, 0, NULL),
('system_feature', 'system', '功能开关', '/system/feature', 'view.system_feature', 'mdi:toggle-switch', 2, 0, NULL),
('trade_order', 'trade', '订单管理', '/trade/order', 'view.trade_order', 'mdi:receipt', 1, 0, NULL),
('trade_order-detail', 'trade', '订单详情', '/trade/order-detail', 'view.trade_order-detail', NULL, 0, 1, NULL),
('trade_payment', 'trade', '支付记录', '/trade/payment', 'view.trade_payment', 'mdi:credit-card', 2, 0, NULL),
('trade_refund', 'trade', '退款管理', '/trade/refund', 'view.trade_refund', 'mdi:cash-refund', 3, 0, NULL),
('trade_verify', 'trade', '核销记录', '/trade/verify', 'view.trade_verify', 'mdi:check-circle', 5, 0, NULL),
('trade_verify-detail', 'trade', '核销记录详情', '/trade/verify-detail', 'view.trade_verify-detail', NULL, 0, 1, NULL),
('trade_verify-pool', 'trade', '待核销池', '/trade/verify-pool', 'view.trade_verify-pool', 'mdi:qrcode-scan', 4, 0, NULL),
('user_list', 'user', '用户列表', '/user/list', 'view.user_list', 'mdi:account', 1, 0, NULL);

-- 【关键】分两步插入，不能一步 JOIN。
--
-- 为什么：`LEFT JOIN sys_menu p ON p.code = s.parent_code` 看到的是 INSERT **之前**的
-- sys_menu 快照 —— 同一条 INSERT 里刚插入的父节点对它不可见，于是所有子节点的
-- parent_id 都落到 0，整棵菜单树被拍平。
-- （2026-09-25 实测踩到：53 条菜单全部 parent_id=0，授权中心子菜单泄漏成顶级菜单，
--   前端动态菜单模式下会渲染成一堆平级菜单，层级完全错乱。）
--
-- 正确做法：先插父节点，再插子节点 —— 此时父节点已在表中，JOIN 才能解析出 parent_id。
INSERT INTO sys_menu (code, parent_id, name, path, component, icon, order_num, type, status, feature_flag)
SELECT s.code, 0, s.title, s.path, s.component, s.icon, s.order_num, 'dir', 1, s.feature_flag
FROM tmp_menu_seed s
WHERE s.parent_code IS NULL
ON DUPLICATE KEY UPDATE
    parent_id    = 0,
    name         = VALUES(name),
    path         = VALUES(path),
    component    = VALUES(component),
    icon         = VALUES(icon),
    order_num    = VALUES(order_num),
    type         = 'dir',
    feature_flag = VALUES(feature_flag),
    deleted      = 0;

-- 子节点：父节点此时已在 sys_menu 中，JOIN 可正常解析出 parent_id
INSERT INTO sys_menu (code, parent_id, name, path, component, icon, order_num, type, status, feature_flag)
SELECT s.code, p.id, s.title, s.path, s.component, s.icon, s.order_num,
       IF(s.hide_flag = 1, 'hidden', 'menu'), 1, s.feature_flag
FROM tmp_menu_seed s
JOIN sys_menu p ON p.code = s.parent_code AND p.deleted = 0
WHERE s.parent_code IS NOT NULL
ON DUPLICATE KEY UPDATE
    parent_id    = p.id,
    name         = VALUES(name),
    path         = VALUES(path),
    component    = VALUES(component),
    icon         = VALUES(icon),
    order_num    = VALUES(order_num),
    type         = IF(VALUES(type) = 'hidden', 'hidden', 'menu'),
    feature_flag = VALUES(feature_flag),
    deleted      = 0;

DROP TEMPORARY TABLE tmp_menu_seed;

-- 「隐藏菜单」标记：前端 hideInMenu 的详情页依然需要参与权限校验，
-- 但不应出现在侧边栏。用 type='hidden' 表达，前端按 type 过滤渲染。
UPDATE sys_menu SET type = 'hidden' WHERE code IN
    ('finance_snapshot-detail', 'review_role-detail', 'trade_order-detail', 'trade_verify-detail');

-- 「微信账号绑定」菜单已下线（需求：角色不再与小程序用户绑定）。
-- 前端页面与路由会一并删除，这里把历史菜单记录逻辑删除，避免动态菜单又把它下发下来。
UPDATE sys_menu SET deleted = 1 WHERE code = 'auth_wechat';

-- ---------- 6. 回填 sys_role_menu：R_OPERATION ----------
-- 「运营」的可见范围以「排除法」表达，与切换前 src/constants/admin.ts 的 roles 数组一致：
--   · 财务中心（finance*）      -> 归 R_FINANCE
--   · 系统审计（system/audit/feature）-> 归 R_AUDIT
--   · 授权中心（auth*）         -> 仅 R_SUPER（需求：其他角色都没有该菜单权限）
--
-- 【为什么不用 `NOT LIKE 'system_audit%'` 之类的模糊排除】
-- 2026-09-25 实测踩到：system_feature（功能开关）在前端 meta 里归属 audit，
-- 但它既不是 'system' 也不匹配 'system_audit%'，被漏网进了运营的菜单。
-- 菜单 code 是人工维护的标识，模糊匹配会在新增菜单时静默失效 ——
-- 因此改为**显式黑名单**：新增菜单时若归运营，无需改这里；
-- 若要给其他角色，必须显式登记，避免"意外获得权限"这种更危险的失败方向。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
FROM sys_role r
JOIN sys_menu m ON m.deleted = 0 AND m.code IS NOT NULL
WHERE r.code = 'R_OPERATION' AND r.deleted = 0
  AND m.code NOT IN (
      -- 财务中心（含全部子菜单，用前缀判定见下方单独语句）
      'finance', 'finance_pool', 'finance_account', 'finance_flow',
      'finance_snapshot', 'finance_snapshot-detail', 'finance_reconcile', 'finance_withdraw',
      -- 系统审计：仅 R_AUDIT
      'system', 'system_audit', 'system_feature',
      -- 授权中心：仅 R_SUPER
      'auth', 'auth_role', 'auth_account', 'auth_grant'
  )
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = m.id
  );

-- ---------- 7. 回填 sys_role_menu：R_FINANCE ----------
-- 「财务」= 财务中心（含子菜单）
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
FROM sys_role r
JOIN sys_menu m ON m.deleted = 0 AND m.code IS NOT NULL
WHERE r.code = 'R_FINANCE' AND r.deleted = 0
  AND (m.code = 'finance' OR m.code LIKE 'finance_%')
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = m.id
  );

-- ---------- 8. 回填 sys_role_menu：R_AUDIT ----------
-- 「审计」= 系统审计（含 审计日志 / 功能开关）；数据字典与城市管理属运营
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
FROM sys_role r
JOIN sys_menu m ON m.deleted = 0 AND m.code IS NOT NULL
WHERE r.code = 'R_AUDIT' AND r.deleted = 0
  AND m.code IN ('system', 'system_audit', 'system_feature')
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = m.id
  );

-- ---------- 9. 授权中心：仅超级管理员可见 ----------
-- 需求原文：「只有超级管理员才有这个菜单的权限，其他角色都没有菜单的权限」。
-- 显式删除其他角色对 auth / auth_* 的关联（防御性清理，覆盖历史上手工加的授权）。
DELETE rm FROM sys_role_menu rm
JOIN sys_role r ON r.id = rm.role_id
JOIN sys_menu m ON m.id = rm.menu_id
WHERE r.code <> 'R_SUPER'
  AND (m.code = 'auth' OR m.code LIKE 'auth_%');

-- 超管角色不写关联记录：后端按「超管直通全量菜单」处理。
-- 这样即使后续新增菜单、忘记给 R_SUPER 补关联，超管也不会被自己锁在门外。

-- ---------- 10. 超管唯一性保护 ----------
-- 需求：「只能拥有一个超级管理员」「超级管理员只能绑定一个账号」。
-- 库里若历史上存在多个 R_SUPER 绑定（如 V21 之前的手工数据），
-- 保留 user_id 最小的那个（最早创建的账号），其余逻辑删除。
--
-- 为什么只在这里修正数据、不加数据库约束：
--   MySQL 无法对「role_id = R_SUPER 且 deleted = 0」建部分唯一索引，
--   唯一性最终由应用层（AdminRbacGuard）保证，迁移负责把存量数据掰正。
UPDATE sys_user_role ur
JOIN sys_role r ON r.id = ur.role_id AND r.code = 'R_SUPER'
SET ur.deleted = 1
WHERE ur.deleted = 0
  AND ur.user_id <> (
      SELECT keep_id FROM (
          SELECT MIN(ur2.user_id) AS keep_id
          FROM sys_user_role ur2
          JOIN sys_role r2 ON r2.id = ur2.role_id AND r2.code = 'R_SUPER'
          WHERE ur2.deleted = 0
      ) t
  );

-- 同步 sys_user.is_super：只有真正绑定了 R_SUPER 的账号才置 1
UPDATE sys_user SET is_super = 0 WHERE deleted = 0;

UPDATE sys_user u
JOIN sys_user_role ur ON ur.user_id = u.id AND ur.deleted = 0
JOIN sys_role r ON r.id = ur.role_id AND r.code = 'R_SUPER' AND r.deleted = 0
SET u.is_super = 1
WHERE u.deleted = 0;

-- ---------- 11. 兜底：确保始终存在一个超管账号 ----------
-- 若原本无任何 R_SUPER 绑定（第 10 步会把所有绑定都标记删除），
-- 则把 id 最小的后台账号认领为超管，避免「无人能进授权中心」的死锁。
INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id
FROM (SELECT id FROM sys_user WHERE deleted = 0 ORDER BY id ASC LIMIT 1) u
JOIN sys_role r ON r.code = 'R_SUPER' AND r.deleted = 0
WHERE NOT EXISTS (
    SELECT 1 FROM sys_user_role ur
    JOIN sys_role r2 ON r2.id = ur.role_id AND r2.code = 'R_SUPER'
    WHERE ur.deleted = 0
)
AND NOT EXISTS (
    SELECT 1 FROM sys_user_role ur2 WHERE ur2.user_id = u.id AND ur2.role_id = r.id
);

UPDATE sys_user SET is_super = 0 WHERE deleted = 0 AND is_super = 1;

UPDATE sys_user SET is_super = 1
WHERE deleted = 0
  AND id = (SELECT keep_id FROM (
      SELECT ur.user_id AS keep_id
      FROM sys_user_role ur
      JOIN sys_role r ON r.id = ur.role_id AND r.code = 'R_SUPER' AND r.deleted = 0
      WHERE ur.deleted = 0
      ORDER BY ur.user_id ASC LIMIT 1
  ) t);


