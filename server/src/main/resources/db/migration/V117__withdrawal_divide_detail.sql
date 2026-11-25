-- =============================================================
-- V117：提现分账明细（对账用）
--
-- 背景：一次提现可能对应「历史多笔订单已分账」的汇总。
-- 为了让运营能回答「这笔提现到底由哪些订单的分账组成」，
-- 需要一张提现 ↔ 订单的关联明细表。
--
-- 与本方案的关系：
--   · 提现出款本身只调一次自助结算，不再逐单分账；
--   · 因此本表的作用是「事后可追溯」，不参与出款流程；
--   · 分账进度以 settlement_record.divide_status 为唯一事实来源，
--     本表只做快照留档，避免 settlement_record 被后续冲正改写后失去历史。
-- =============================================================

CREATE TABLE IF NOT EXISTS withdrawal_divide_detail (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    withdrawal_id       BIGINT UNSIGNED NOT NULL COMMENT '提现单 id',
    withdrawal_no       VARCHAR(64) NOT NULL COMMENT '提现单号（冗余便于查询）',
    settlement_record_id BIGINT UNSIGNED NULL COMMENT '结算记录 id',
    order_id            BIGINT UNSIGNED NULL,
    order_no            VARCHAR(64) NULL,
    divide_request_id   VARCHAR(64) NULL COMMENT '该订单的分账请求号',
    divide_amount       BIGINT NOT NULL DEFAULT 0 COMMENT '分账金额（分）',
    divide_status       VARCHAR(32) NULL COMMENT '分账状态快照',
    unique_divide_no    VARCHAR(64) NULL COMMENT '易宝分账流水号',
    create_time         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_withdrawal_divide_no (withdrawal_no),
    KEY idx_withdrawal_divide_order (order_no),
    KEY idx_withdrawal_divide_request (divide_request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='提现分账明细（对账留档）';
