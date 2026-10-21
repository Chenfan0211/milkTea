-- =============================================================
-- 五零时光 · 测试数据清理校验脚本
-- 配套：scripts/clean-test-data.sql（清理后立即执行）
-- 依据：docs/测试数据清理方案.md
-- =============================================================

-- B1. 流水表：期望全部为 0
SELECT 'orders', COUNT(*) FROM orders
UNION ALL SELECT 'order_item', COUNT(*) FROM order_item
UNION ALL SELECT 'payment', COUNT(*) FROM payment
UNION ALL SELECT 'refund', COUNT(*) FROM refund
UNION ALL SELECT 'verify_record', COUNT(*) FROM verify_record
UNION ALL SELECT 'comments', COUNT(*) FROM comments
UNION ALL SELECT 'user_coupon', COUNT(*) FROM user_coupon
UNION ALL SELECT 'points_record', COUNT(*) FROM points_record
UNION ALL SELECT 'points_signin', COUNT(*) FROM points_signin
UNION ALL SELECT 'growth_record', COUNT(*) FROM growth_record
UNION ALL SELECT 'stored_value_order', COUNT(*) FROM stored_value_order
UNION ALL SELECT 'stored_value_txn', COUNT(*) FROM stored_value_txn
UNION ALL SELECT 'balance_pay_intent', COUNT(*) FROM balance_pay_intent
UNION ALL SELECT 'gift_card', COUNT(*) FROM gift_card
UNION ALL SELECT 'gift_card_order', COUNT(*) FROM gift_card_order
UNION ALL SELECT 'exchange_order', COUNT(*) FROM exchange_order
UNION ALL SELECT 'referral_record', COUNT(*) FROM referral_record
UNION ALL SELECT 'settlement_record', COUNT(*) FROM settlement_record
UNION ALL SELECT 'fund_flow', COUNT(*) FROM fund_flow
UNION ALL SELECT 'fund_pool', COUNT(*) FROM fund_pool
UNION ALL SELECT 'split_snapshot', COUNT(*) FROM split_snapshot
UNION ALL SELECT 'reconcile_issue', COUNT(*) FROM reconcile_issue
UNION ALL SELECT 'withdrawal', COUNT(*) FROM withdrawal
UNION ALL SELECT 'role_application', COUNT(*) FROM role_application
UNION ALL SELECT 'audit_log', COUNT(*) FROM audit_log
UNION ALL SELECT 'event_outbox', COUNT(*) FROM event_outbox;

-- B2. 保留表：实际值应等于名称中的期望值
SELECT 'sys_user=5', COUNT(*) FROM sys_user
UNION ALL SELECT 'sys_role=4', COUNT(*) FROM sys_role
UNION ALL SELECT 'sys_user_role=6', COUNT(*) FROM sys_user_role
UNION ALL SELECT 'sys_menu=54', COUNT(*) FROM sys_menu
UNION ALL SELECT 'sys_role_menu=52', COUNT(*) FROM sys_role_menu
UNION ALL SELECT 'biz_subject=15', COUNT(*) FROM biz_subject
UNION ALL SELECT 'store_profile=5', COUNT(*) FROM store_profile
UNION ALL SELECT 'app_user=7', COUNT(*) FROM app_user
UNION ALL SELECT 'user_role_grant=4', COUNT(*) FROM user_role_grant
UNION ALL SELECT 'member_level=3', COUNT(*) FROM member_level
UNION ALL SELECT 'split_rule=3', COUNT(*) FROM split_rule
UNION ALL SELECT 'product=18', COUNT(*) FROM product
UNION ALL SELECT 'product_spec=116', COUNT(*) FROM product_spec
UNION ALL SELECT 'product_store=89', COUNT(*) FROM product_store
UNION ALL SELECT 'coupon=11', COUNT(*) FROM coupon
UNION ALL SELECT 'region=3427', COUNT(*) FROM region
UNION ALL SELECT 'app_config=13', COUNT(*) FROM app_config
UNION ALL SELECT 'points_product=7', COUNT(*) FROM points_product
UNION ALL SELECT 'gift_card_denomination=9', COUNT(*) FROM gift_card_denomination
UNION ALL SELECT 'stored_value_package=3', COUNT(*) FROM stored_value_package;

-- B3. 余额：期望为 0
SELECT 'subject_account_nonzero_balance', COUNT(*) FROM subject_account
WHERE available_balance <> 0 OR frozen_balance <> 0
   OR total_income <> 0 OR total_withdrawn <> 0;

SELECT 'app_user_nonzero_wallet', COUNT(*) FROM app_user
WHERE points <> 0 OR balance <> 0 OR total_spend <> 0;

-- B4. 残留测试痕迹：期望为 0
SELECT 'leftover_demo_rows',
       (SELECT COUNT(*) FROM product      WHERE product_id LIKE 'DEMO-P%')
     + (SELECT COUNT(*) FROM orders       WHERE order_no   LIKE 'DEMO-O%')
     + (SELECT COUNT(*) FROM member_level WHERE LEFT(level_code, 2) = '__' OR name LIKE '%测试%')
     + (SELECT COUNT(*) FROM biz_subject  WHERE LEFT(code, 2) = '__' OR code IN ('SU-TEST37','ST-1006'))
     + (SELECT COUNT(*) FROM split_rule   WHERE code IN ('SR-BAD','SR-TEST'));

-- B4b. 悬空引用：期望为 0
SELECT 'dangling_refs',
       (SELECT COUNT(*) FROM app_user u WHERE u.referrer_id IS NOT NULL
          AND NOT EXISTS (SELECT 1 FROM app_user x WHERE x.id = u.referrer_id))
     + (SELECT COUNT(*) FROM biz_subject b WHERE b.bound_user_id IS NOT NULL
          AND NOT EXISTS (SELECT 1 FROM app_user x WHERE x.id = b.bound_user_id))
     + (SELECT COUNT(*) FROM user_role_grant g WHERE NOT EXISTS (SELECT 1 FROM app_user u WHERE u.id = g.user_id));

-- B5. Flyway 历史：期望 74 条且全部 success（不可被清理影响）
SELECT 'flyway_applied', COUNT(*) FROM flyway_schema_history WHERE success = 1;
