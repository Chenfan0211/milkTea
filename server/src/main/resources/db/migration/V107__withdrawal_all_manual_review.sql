-- 所有提现金额统一进入运营后台审核；历史免审阈值停止生效。
UPDATE biz_subject
SET withdraw_free_audit_threshold = 0
WHERE withdraw_free_audit_threshold <> 0;

-- 保留运营配置中的费用及失败说明，仅替换与免审相关的旧文案。
UPDATE app_config
SET value = JSON_SET(
    COALESCE(value, JSON_OBJECT()),
    '$.instantLimit', '¥0.00',
    '$.instantNote', '所有提现申请均需运营后台审核，无免审额度',
    '$.auditNote', '所有提现申请均需运营后台审核，审核通过后发起出款'
)
WHERE config_key = 'withdraw_rule' AND deleted = 0;
