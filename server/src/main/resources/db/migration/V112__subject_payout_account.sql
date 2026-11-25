-- =============================================================
-- V112：经营者结算银行卡（易宝 settle-card）
--
-- 背景：订单分账路线下，钱从平台待分账账户分到经营者「子商户待结算账户」，
-- 再通过 settle/self-settle/apply 结算到已绑定的「结算卡」。
-- 注意这里绑的是「结算卡」而非「提现卡」：两者是易宝不同产品，
-- 结算卡走 settle/settle-card/add，提现卡走 account/withdraw/card/bind。
-- 本表只承载结算卡。
--
-- 敏感信息处理：
--   bank_card_no_enc  AES 加密存储，密钥来自环境变量（不入库、不入仓库）
--   bank_card_mask    仅存掩码，列表与详情展示一律用掩码
-- =============================================================

CREATE TABLE IF NOT EXISTS subject_payout_account (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    subject_id          BIGINT UNSIGNED NOT NULL COMMENT '经营主体 biz_subject.id',
    user_id             BIGINT UNSIGNED NULL COMMENT '绑定操作人 app_user.id',
    account_type        VARCHAR(16) NOT NULL COMMENT 'PUBLIC 对公 / PRIVATE 对私（个体或小微）',
    account_name        VARCHAR(128) NOT NULL COMMENT '开户名（须与签约主体一致）',
    bank_card_no_enc    VARCHAR(512) NOT NULL COMMENT '银行卡号（AES 加密）',
    bank_card_mask      VARCHAR(32) NOT NULL COMMENT '卡号掩码，如 **** **** **** 1234',
    bank_code           VARCHAR(32) NULL COMMENT '开户总行编码（易宝银行编码表）',
    branch_code         VARCHAR(32) NULL COMMENT '开户支行编码（农信社 / 小额农行建议填）',
    bank_name           VARCHAR(64) NULL COMMENT '开户行名称（展示用）',
    yeepay_settle_card_id VARCHAR(64) NULL COMMENT '易宝结算卡 ID（settle-card/add 返回）',
    verify_status       VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                        COMMENT 'PENDING 待校验 / PASS 已校验 / FAIL 校验失败',
    verify_msg          VARCHAR(255) NULL COMMENT '易宝返回的校验失败原因',
    is_default          TINYINT NOT NULL DEFAULT 0 COMMENT '是否默认结算卡',
    status              VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED',
    create_time         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted             TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payout_account_card (subject_id, bank_card_mask, deleted),
    KEY idx_payout_account_subject (subject_id, is_default),
    KEY idx_payout_account_yeepay (yeepay_settle_card_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='经营者结算银行卡';

-- 同一主体最多一张默认卡：用生成列 + 唯一索引在数据库层强制，
-- 避免应用层并发设置默认卡出现两张。
-- MySQL 8.0 支持函数索引，此处用生成列更直观。
ALTER TABLE subject_payout_account
    ADD COLUMN default_flag TINYINT
        GENERATED ALWAYS AS (IF(is_default = 1 AND deleted = 0, 1, NULL)) VIRTUAL
        COMMENT '默认卡唯一约束辅助列',
    ADD UNIQUE KEY uk_payout_account_one_default (subject_id, default_flag);
