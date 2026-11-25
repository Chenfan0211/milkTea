-- =============================================================
-- V115：经营者押金（退款兜底）
--
-- 背景：现有退款冲正逻辑（LedgerService.reverseInTransaction）从
-- 可用余额扣回，余额不足时整批回滚并记 REFUND_BALANCE_SHORTAGE 对账异常。
-- 经营者在提现后再发生售后退款时，退款会失败，只能人工介入。
--
-- 押金就是来堵这个缺口的：
--   退款冲正顺序改为「可用余额 → 押金 → 对账异常」
--
-- 设计红线：押金是**纯本地账本额度**，对应的钱留在平台主账户，
-- 永不参与分账、永不出款。若把押金分账到子商户，经营者可在易宝
-- 商户后台自行结算，押金就彻底失控了。
--
-- 不可提现的三重保障：
--   1. WithdrawalService.apply 只从 available_balance 取钱；
--   2. 前端可提现额只读 availableBalance，押金单独一行标注不可提现；
--   3. LedgerService.validatePostingType 强制 WITHDRAW 只能作用于 FROZEN 桶。
-- =============================================================

-- 押金余额桶（第三桶）
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'subject_account'
               AND COLUMN_NAME = 'deposit_balance');
SET @sql := IF(@col = 0,
  'ALTER TABLE subject_account ADD COLUMN deposit_balance BIGINT NOT NULL DEFAULT 0 COMMENT ''押金余额（分，不可提现）''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 押金非负约束
SET @ck := (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'subject_account'
              AND CONSTRAINT_NAME = 'ck_subject_account_deposit_non_negative');
SET @sql := IF(@ck = 0,
  'ALTER TABLE subject_account ADD CONSTRAINT ck_subject_account_deposit_non_negative CHECK (deposit_balance >= 0)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 押金台账：每次设定/调整/抵扣一条，供运营查询与纠纷追溯
CREATE TABLE IF NOT EXISTS deposit_record (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    record_no       VARCHAR(64) NOT NULL COMMENT '台账单号',
    subject_id      BIGINT UNSIGNED NOT NULL,
    change_amount   BIGINT NOT NULL COMMENT '变动金额（分），正增负减',
    balance_before  BIGINT NOT NULL COMMENT '变动前押金余额',
    balance_after   BIGINT NOT NULL COMMENT '变动后押金余额',
    biz_type        VARCHAR(32) NOT NULL
                    COMMENT 'SET 运营设定 / ADJUST 运营调整 / DEDUCT_REFUND 退款抵扣 / REFUND_BACK 抵扣返还',
    biz_no          VARCHAR(128) NULL COMMENT '业务单号：退款时写 refundNo，设定时写操作流水号',
    order_no        VARCHAR(64) NULL COMMENT '关联订单号（退款抵扣时必填，供经营者查看原因）',
    operator        VARCHAR(64) NULL COMMENT '操作人（运营账号）',
    reason          VARCHAR(512) NULL COMMENT '变动原因（退款抵扣须写清订单号与退款单号）',
    create_time     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_deposit_record_no (record_no),
    KEY idx_deposit_subject (subject_id, create_time),
    KEY idx_deposit_biz_no (biz_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='经营者押金台账';

-- fund_flow.balance_bucket 允许值扩展：AVAILABLE / FROZEN / DEPOSIT
-- （列本身是 VARCHAR，无需改结构；此处仅更新注释以便后续维护者知晓）
ALTER TABLE fund_flow MODIFY COLUMN balance_bucket VARCHAR(16) NULL
    COMMENT '余额桶 AVAILABLE 可用 / FROZEN 冻结 / DEPOSIT 押金';
