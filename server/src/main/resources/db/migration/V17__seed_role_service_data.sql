-- =============================================================
-- 经营角色体系 + 客服配置 seed（阶段 C 假数据清理 · 补充）
-- 来源：user-h5/data/role-mock.js 与 user-h5/data/service.js
--
-- 归属：模拟业务数据绑定到真实 subject
--   store    -> subject_id 101 (星沙乐运魔方店)
--   investor -> subject_id 201 (投资人甲)
--   resource -> subject_id 301 (资源方甲)
-- 金额单位：分。演示数据 record_no/withdraw_no 统一加 DEMO- 前缀，
-- 与真实业务数据（SR.../WD2026...）区分。
-- =============================================================

-- ---------- 1. 配置类数据（app_config） ----------
INSERT INTO app_config (config_key, config_name, value, sort, remark) VALUES
('settlement_notes', '结算说明', '["仅按订单实付金额分账，时光币兑换与抵扣部分不参与分账","收益入账后先为待结算，T+1 转为可结算，退款订单同步冲正","分账比例以万分比维护，金额统一精确到分，尾差归平台"]', 8, '阶段C补充：经营角色/客服配置')
ON DUPLICATE KEY UPDATE config_name=VALUES(config_name), value=VALUES(value), sort=VALUES(sort), remark=VALUES(remark);

INSERT INTO app_config (config_key, config_name, value, sort, remark) VALUES
('service_info', '客服信息', '{"hotline":"400-000-0000","hotlineNote":"热线号码为占位配置，正式上线前需替换","serviceHours":"每日 9:00 - 21:00","onlineNote":"在线客服由微信提供，会话记录可在微信内查看","responseNote":"客服将在收到消息后尽快回复，高峰期可能延迟"}', 9, '阶段C补充：经营角色/客服配置')
ON DUPLICATE KEY UPDATE config_name=VALUES(config_name), value=VALUES(value), sort=VALUES(sort), remark=VALUES(remark);

INSERT INTO app_config (config_key, config_name, value, sort, remark) VALUES
('service_faqs', '常见问题', '[{"id":"order-cancel","question":"如何取消订单？","answer":"在【订单】页找到对应订单，进入订单详情后可自行取消。已支付订单在门店开始制作前可申请退款，退款按原支付路径退回。"},{"id":"stored-value","question":"会员储值余额如何使用？","answer":"储值余额可用于本小程序内下单支付。在订单确认页选择「储值支付」即可使用，余额不可提现、不可转让。"},{"id":"coupon-stack","question":"优惠券为什么不能叠加？","answer":"同一订单默认仅可使用一张优惠券。部分活动商品不参与优惠券抵扣，具体适用范围以优惠券详情页展示为准。"},{"id":"points-expire","question":"时光币有效期是多久？","answer":"时光币存在有效期限制，过期未使用的时光币会自动失效，请在有效期内通过【时光币兑换】使用。"},{"id":"pickup-code","question":"取餐码在哪里查看？","answer":"下单成功后可在【订单】页或订单详情页查看取餐码。到店后向店员出示取餐码或核销二维码即可。"},{"id":"invoice","question":"如何开具发票？","answer":"发票功能正在接入中，如需开票可通过在线客服联系我们，提供订单号后由客服协助处理。"}]', 10, '阶段C补充：经营角色/客服配置')
ON DUPLICATE KEY UPDATE config_name=VALUES(config_name), value=VALUES(value), sort=VALUES(sort), remark=VALUES(remark);

INSERT INTO app_config (config_key, config_name, value, sort, remark) VALUES
('withdraw_rule', '提现规则', '{"instantLimit":"¥100.00","instantNote":"小额即时到账，无需人工审核","auditNote":"超过即时额度需后台审核，审核通过后出款","feeNote":"提现手续费与单笔上限由后台配置","failureNote":"失败或驳回将自动解冻对应金额"}', 11, '阶段C补充：经营角色/客服配置')
ON DUPLICATE KEY UPDATE config_name=VALUES(config_name), value=VALUES(value), sort=VALUES(sort), remark=VALUES(remark);

-- ---------- 2. 核销记录（verify_record，store subject 101，DEMO 前缀） ----------
INSERT INTO verify_record (verify_code, order_no, type, store_subject_id, result) VALUES
('DEMO-A026', 'DEMO-D00235803499139801085', 'order', 101, 'success')
ON DUPLICATE KEY UPDATE result=VALUES(result);
INSERT INTO verify_record (verify_code, order_no, type, store_subject_id, result) VALUES
('DEMO-B012', 'DEMO-D00235803499139801087', 'order', 101, 'success')
ON DUPLICATE KEY UPDATE result=VALUES(result);
INSERT INTO verify_record (verify_code, order_no, type, store_subject_id, result) VALUES
('DEMO-A015', 'DEMO-D00235803499139801090', 'order', 101, 'success')
ON DUPLICATE KEY UPDATE result=VALUES(result);

-- ---------- 3. 提现记录（withdrawal，DEMO 前缀，正确枚举） ----------
INSERT INTO withdrawal (withdraw_no, user_id, subject_id, role_type, amount, fee, status, apply_time, failure_reason) VALUES
('DEMO-WD202609200003', 1, 101, 'STORE', 50000, 0, 'APPLIED', '2026-09-20 15:42:08', NULL)
ON DUPLICATE KEY UPDATE status=VALUES(status);
INSERT INTO withdrawal (withdraw_no, user_id, subject_id, role_type, amount, fee, status, apply_time, failure_reason) VALUES
('DEMO-WD202609190002', 1, 101, 'STORE', 8800, 0, 'APPROVED', '2026-09-19 11:08:36', NULL)
ON DUPLICATE KEY UPDATE status=VALUES(status);
INSERT INTO withdrawal (withdraw_no, user_id, subject_id, role_type, amount, fee, status, apply_time, failure_reason) VALUES
('DEMO-WD202609120001', 1, 101, 'STORE', 120000, 600, 'PAID', '2026-09-12 09:24:15', NULL)
ON DUPLICATE KEY UPDATE status=VALUES(status);
INSERT INTO withdrawal (withdraw_no, user_id, subject_id, role_type, amount, fee, status, apply_time, failure_reason) VALUES
('DEMO-WD202609050001', 1, 101, 'STORE', 200000, 0, 'REJECTED', '2026-09-05 20:16:47', '收款账户信息有误，请核对后重新提交')
ON DUPLICATE KEY UPDATE status=VALUES(status);
INSERT INTO withdrawal (withdraw_no, user_id, subject_id, role_type, amount, fee, status, apply_time, failure_reason) VALUES
('DEMO-WD202609210001', 1, 201, 'INVESTOR', 61200, 0, 'APPLIED', '2026-09-21 10:05:22', NULL)
ON DUPLICATE KEY UPDATE status=VALUES(status);
INSERT INTO withdrawal (withdraw_no, user_id, subject_id, role_type, amount, fee, status, apply_time, failure_reason) VALUES
('DEMO-WD202609150002', 1, 201, 'INVESTOR', 180600, 903, 'PAID', '2026-09-15 14:32:51', NULL)
ON DUPLICATE KEY UPDATE status=VALUES(status);
INSERT INTO withdrawal (withdraw_no, user_id, subject_id, role_type, amount, fee, status, apply_time, failure_reason) VALUES
('DEMO-WD202609080001', 1, 201, 'INVESTOR', 9600, 0, 'APPROVED', '2026-09-08 19:47:03', NULL)
ON DUPLICATE KEY UPDATE status=VALUES(status);
INSERT INTO withdrawal (withdraw_no, user_id, subject_id, role_type, amount, fee, status, apply_time, failure_reason) VALUES
('DEMO-WD202609210002', 1, 301, 'CHANNEL', 8640, 0, 'APPROVED', '2026-09-21 08:19:30', NULL)
ON DUPLICATE KEY UPDATE status=VALUES(status);
INSERT INTO withdrawal (withdraw_no, user_id, subject_id, role_type, amount, fee, status, apply_time, failure_reason) VALUES
('DEMO-WD202609140001', 1, 301, 'CHANNEL', 340000, 1700, 'PAID', '2026-09-14 16:58:12', NULL)
ON DUPLICATE KEY UPDATE status=VALUES(status);

-- ---------- 4. 收益流水（settlement_record，DEMO 前缀，正确枚举） ----------
INSERT INTO settlement_record (record_no, subject_id, amount, status, settle_date, create_time) VALUES
('DEMO-IC202609180004', 101, 1890, 'PENDING', '2026-09-18', '2026-09-18 20:31:02')
ON DUPLICATE KEY UPDATE status=VALUES(status);
INSERT INTO settlement_record (record_no, subject_id, amount, status, settle_date, create_time) VALUES
('DEMO-IC202609180003', 101, 1490, 'PENDING', '2026-09-18', '2026-09-18 19:16:44')
ON DUPLICATE KEY UPDATE status=VALUES(status);
INSERT INTO settlement_record (record_no, subject_id, amount, status, settle_date, create_time) VALUES
('DEMO-IC202609170002', 101, 2780, 'SETTLED', '2026-09-17', '2026-09-17 15:08:20')
ON DUPLICATE KEY UPDATE status=VALUES(status);
INSERT INTO settlement_record (record_no, subject_id, amount, status, settle_date, create_time) VALUES
('DEMO-IC202609160001', 101, 1890, 'CANCELED', '2026-09-16', '2026-09-16 11:42:36')
ON DUPLICATE KEY UPDATE status=VALUES(status);
INSERT INTO settlement_record (record_no, subject_id, amount, status, settle_date, create_time) VALUES
('DEMO-IC202608310002', 201, 180600, 'SETTLED', '2026-08-31', '2026-08-31 23:59:59')
ON DUPLICATE KEY UPDATE status=VALUES(status);
INSERT INTO settlement_record (record_no, subject_id, amount, status, settle_date, create_time) VALUES
('DEMO-IC202608310001', 201, 61200, 'PENDING', '2026-08-31', '2026-08-31 23:59:58')
ON DUPLICATE KEY UPDATE status=VALUES(status);
INSERT INTO settlement_record (record_no, subject_id, amount, status, settle_date, create_time) VALUES
('DEMO-IC202607310001', 201, 174000, 'SETTLED', '2026-07-31', '2026-07-31 23:59:59')
ON DUPLICATE KEY UPDATE status=VALUES(status);
INSERT INTO settlement_record (record_no, subject_id, amount, status, settle_date, create_time) VALUES
('DEMO-IC202607150003', 201, 8600, 'CANCELED', '2026-07-15', '2026-07-15 16:20:11')
ON DUPLICATE KEY UPDATE status=VALUES(status);
INSERT INTO settlement_record (record_no, subject_id, amount, status, settle_date, create_time) VALUES
('DEMO-IC202609210002', 301, 420, 'PENDING', '2026-09-21', '2026-09-21 08:19:30')
ON DUPLICATE KEY UPDATE status=VALUES(status);
INSERT INTO settlement_record (record_no, subject_id, amount, status, settle_date, create_time) VALUES
('DEMO-IC202609210001', 301, 360, 'PENDING', '2026-09-21', '2026-09-21 07:40:12')
ON DUPLICATE KEY UPDATE status=VALUES(status);
INSERT INTO settlement_record (record_no, subject_id, amount, status, settle_date, create_time) VALUES
('DEMO-IC202609200004', 301, 630, 'SETTLED', '2026-09-20', '2026-09-20 15:08:44')
ON DUPLICATE KEY UPDATE status=VALUES(status);
INSERT INTO settlement_record (record_no, subject_id, amount, status, settle_date, create_time) VALUES
('DEMO-IC202609190001', 301, 360, 'CANCELED', '2026-09-19', '2026-09-19 13:26:05')
ON DUPLICATE KEY UPDATE status=VALUES(status);

-- ---------- 5. 资金流水（fund_flow，DEMO 前缀） ----------
INSERT INTO fund_flow (flow_no, subject_id, role_type, type, direction, amount, order_no, balance_after, remark) VALUES
('DEMO-FF202609200003', 101, 'STORE', 'WITHDRAW', 'out', 50000, 'DEMO-WD202609200003', 120600, '提现申请冻结')
ON DUPLICATE KEY UPDATE remark=VALUES(remark);
INSERT INTO fund_flow (flow_no, subject_id, role_type, type, direction, amount, order_no, balance_after, remark) VALUES
('DEMO-FF202609190002', 101, 'STORE', 'WITHDRAW', 'out', 8800, 'DEMO-WD202609190002', 120600, '提现出款')
ON DUPLICATE KEY UPDATE remark=VALUES(remark);
INSERT INTO fund_flow (flow_no, subject_id, role_type, type, direction, amount, order_no, balance_after, remark) VALUES
('DEMO-FF202609210001', 201, 'INVESTOR', 'WITHDRAW', 'out', 61200, 'DEMO-WD202609210001', 180600, '提现申请冻结')
ON DUPLICATE KEY UPDATE remark=VALUES(remark);
INSERT INTO fund_flow (flow_no, subject_id, role_type, type, direction, amount, order_no, balance_after, remark) VALUES
('DEMO-FF202609210002', 301, 'CHANNEL', 'WITHDRAW', 'out', 8640, 'DEMO-WD202609210002', 342050, '提现申请冻结')
ON DUPLICATE KEY UPDATE remark=VALUES(remark);
