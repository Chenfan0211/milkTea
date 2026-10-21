-- =============================================================
-- 五零时光 · 测试数据清理（一次性脚本，2026-10-14）
--
-- 目标库：wuling（生产 43.136.91.239 / 容器 wuling-mysql）
-- 保留：账户 / 权限 / 字典 / 区域 / 配置 / 运营主数据
-- 清空：业务流水；归零：账户余额；定点删：保留表内演示与探针行
-- 特性：幂等、单事务、可重复执行（异常自动回滚）
-- 依据：docs/测试数据清理方案.md
--
-- 执行方式（口令走 secrets.env，不进命令行明文）：
--   bash /opt/wuling/scripts/db-exec.sh < clean-test-data.sql
--
-- 闸门：执行前必须先完成全库备份并校验 sha256。
-- =============================================================

SET FOREIGN_KEY_CHECKS = 0;          -- 实测全库无外键，仅作防御

START TRANSACTION;

-- ---------- 1. 业务流水整体清空（27 张表）----------
DELETE FROM order_item;              -- 60
DELETE FROM payment;                 -- 41
DELETE FROM refund;                  -- 13
DELETE FROM verify_record;           -- 22
DELETE FROM comments;                -- 7
DELETE FROM balance_pay_intent;      -- 13
DELETE FROM stored_value_txn;        -- 15
DELETE FROM stored_value_order;      -- 8
DELETE FROM exchange_order;          -- 3
DELETE FROM gift_card;               -- 4
DELETE FROM gift_card_order;         -- 6
DELETE FROM gift_card_refund;        -- 0
DELETE FROM growth_record;           -- 7
DELETE FROM points_record;           -- 26
DELETE FROM points_signin;           -- 10
DELETE FROM user_coupon;             -- 22（券模板 coupon 保留）
DELETE FROM referral_record;         -- 15
DELETE FROM split_snapshot;          -- 15
DELETE FROM settlement_record;       -- 74
DELETE FROM fund_flow;               -- 134
DELETE FROM fund_pool;               -- 6
DELETE FROM reconcile_issue;         -- 17
DELETE FROM withdrawal;              -- 20
DELETE FROM role_application;        -- 12
DELETE FROM orders;                  -- 52
DELETE FROM audit_log;               -- 260（要留痕操作日志则注释本行）
DELETE FROM event_outbox;            -- 51（全 SENT）

-- ---------- 2. 账户保留、余额归零 ----------
UPDATE subject_account
SET available_balance = 0,
    frozen_balance    = 0,
    total_income      = 0,
    total_withdrawn   = 0,
    version           = version + 1;

UPDATE app_user
SET points      = 0,
    balance     = 0,
    total_spend = 0;

-- ---------- 3. 保留表内的演示 / 探针脏行 ----------
-- 3.1 演示商品（DEMO-P001~P012）：先删从表，再删主表
DELETE FROM product_store
WHERE product_id IN (SELECT id FROM (SELECT id FROM product WHERE product_id LIKE 'DEMO-P%') t);
DELETE FROM product_spec
WHERE product_id IN (SELECT id FROM (SELECT id FROM product WHERE product_id LIKE 'DEMO-P%') t);
DELETE FROM product WHERE product_id LIKE 'DEMO-P%';

-- 3.2 会员等级：删探针档与 Lv4（原「测试卡」）
DELETE FROM member_level
WHERE level_code IN ('__VLv9__', '__PLv9__', 'Lv4');

-- 3.3 分账规则：删测试与非法比例规则（保留 SR-1000 / SR-1001 / SR-1002）
DELETE FROM split_rule WHERE code IN ('SR-BAD', 'SR-TEST');

-- 3.4 经营主体：删探针 / 验证 / 测试主体
--     保留 1（平台）/ 101-105（门店）/ 201-203（投资人）/ 301-303（渠道）/ 401-403（供应商）
DELETE FROM biz_subject
WHERE LEFT(code, 2) = '__'
   OR code IN ('SU-TEST37', 'ST-1006')
   OR name LIKE '%测试%'
   OR name LIKE '%验证%';

-- 3.5 清理主体档案中的孤立行（主体已被删掉的档案）
DELETE FROM store_profile    WHERE NOT EXISTS (SELECT 1 FROM biz_subject b WHERE b.id = store_profile.subject_id);
DELETE FROM supplier_profile WHERE NOT EXISTS (SELECT 1 FROM biz_subject b WHERE b.id = supplier_profile.subject_id);
DELETE FROM investor_profile WHERE NOT EXISTS (SELECT 1 FROM biz_subject b WHERE b.id = investor_profile.subject_id);
DELETE FROM channel_profile  WHERE NOT EXISTS (SELECT 1 FROM biz_subject b WHERE b.id = channel_profile.subject_id);

-- 3.6 演示小程序账号（假 openid）及其角色授予
DELETE FROM user_role_grant
WHERE user_id IN (SELECT id FROM (SELECT id FROM app_user WHERE open_id LIKE 'oDemoUser%') t);
DELETE FROM app_user WHERE open_id LIKE 'oDemoUser%';

-- 3.7 清理授予表中的孤立行与重复行
DELETE FROM user_role_grant
WHERE NOT EXISTS (SELECT 1 FROM app_user u WHERE u.id = user_role_grant.user_id)
   OR NOT EXISTS (SELECT 1 FROM biz_subject b WHERE b.id = user_role_grant.subject_id);

DELETE g1 FROM user_role_grant g1
JOIN user_role_grant g2
  ON g1.user_id    = g2.user_id
 AND g1.role_code  = g2.role_code
 AND g1.subject_id = g2.subject_id
 AND g1.id > g2.id;

-- 3.8 清理保留数据中的悬空引用（指向已删数据的外键值）
UPDATE app_user SET referrer_id = NULL
WHERE referrer_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM (SELECT id FROM app_user) u WHERE u.id = app_user.referrer_id);

UPDATE app_user SET channel_subject_id = NULL
WHERE channel_subject_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM biz_subject b WHERE b.id = app_user.channel_subject_id);

UPDATE app_user SET bound_subject_id = NULL
WHERE bound_subject_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM biz_subject b WHERE b.id = app_user.bound_subject_id);

-- 主体上残留的演示绑定账号（如门店 101 原绑定演示账号 id=1）
UPDATE biz_subject SET bound_user_id = NULL
WHERE bound_user_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM (SELECT id FROM app_user) u WHERE u.id = biz_subject.bound_user_id);

-- ---------- 4. 决策点：若确认「演示渠道归因」也要清，放开下面一行 ----------
-- DELETE FROM channel_store;        -- 8 行（渠道 301-303 → 门店 101-105）

COMMIT;

SET FOREIGN_KEY_CHECKS = 1;

-- 可选（非必需）：重置自增；DDL 会隐式提交，故必须放在事务之外
-- ALTER TABLE orders AUTO_INCREMENT = 1;
-- ALTER TABLE order_item AUTO_INCREMENT = 1;

-- =============================================================
-- 以下为清理后需「单独确认」再执行的不可逆项，默认注释
-- ----------
-- DROP TABLE IF EXISTS _bak_super_20260925;
-- DROP TABLE IF EXISTS flyway_schema_history_bak_20261010;

-- DROP DATABASE IF EXISTS wuling_v48_rehearsal_20260926_172109;
-- DROP DATABASE IF EXISTS wuling_v48_rehearsal_20260926_172126;
-- =============================================================
